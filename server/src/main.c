#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#else
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#endif

#include "protocol.h"
#include "platform.h"
#include "noise_session.h"
#include "pairing_store.h"

#define BUF_SIZE 4096
#define ANNOUNCE_INTERVAL_SEC 5
#define MAX_CLIENTS 4

const uint8_t TELEPAD_DISCOVERY_MAGIC[TELEPAD_DISCOVERY_MAGIC_LEN] = {
    0x54, 0xE7, 0x9A, 0x03, 0x21, 0xC8, 0xBE, 0xFE
};

typedef struct {
    struct sockaddr_in addr;
    socklen_t          addr_len;
    telepad_noise_t    noise;
    time_t             last_seen;
    int                in_use;
} client_ctx_t;

static client_ctx_t g_clients[MAX_CLIENTS];
static char         g_hostname[128] = "Telepad PC";
static int          g_verbose = 0;

static void get_hostname(void) {
#ifdef _WIN32
    DWORD size = sizeof(g_hostname);
    if (!GetComputerNameA(g_hostname, &size)) {
        strncpy(g_hostname, "Telepad PC", sizeof(g_hostname));
    }
#else
    gethostname(g_hostname, sizeof(g_hostname));
#endif
    g_hostname[sizeof(g_hostname) - 1] = '\0';
}

/* Look up an existing client by address. Returns NULL if not found. */
static client_ctx_t *find_existing_client(struct sockaddr_in *addr,
                                          socklen_t addr_len) {
    (void)addr_len;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (g_clients[i].in_use &&
            g_clients[i].addr.sin_addr.s_addr == addr->sin_addr.s_addr &&
            g_clients[i].addr.sin_port == addr->sin_port) {
            return &g_clients[i];
        }
    }
    return NULL;
}

/* Allocate a client slot. Evicts the oldest if all are full. */
static client_ctx_t *allocate_client_slot(struct sockaddr_in *addr,
                                          socklen_t addr_len) {
    int free_slot = -1;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (!g_clients[i].in_use) { free_slot = i; break; }
    }
    if (free_slot < 0) {
        /* All slots used — evict the oldest. */
        time_t oldest = (time_t)-1;
        int    victim = 0;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (oldest == (time_t)-1 || g_clients[i].last_seen < oldest) {
                oldest = g_clients[i].last_seen;
                victim = i;
            }
        }
        telepad_noise_session_free(&g_clients[victim].noise);
        free_slot = victim;
    }
    memset(&g_clients[free_slot], 0, sizeof(client_ctx_t));
    memcpy(&g_clients[free_slot].addr, addr, sizeof(*addr));
    g_clients[free_slot].addr_len = addr_len;
    g_clients[free_slot].in_use = 1;
    g_clients[free_slot].last_seen = time(NULL);
    return &g_clients[free_slot];
}

static void send_pong(int sockfd, struct sockaddr_in *addr, socklen_t addr_len) {
    char buf[256];
    int  len = snprintf(buf, sizeof(buf), "TELEPAD_PONG:%s", g_hostname);
    sendto(sockfd, buf, len, 0, (struct sockaddr *)addr, addr_len);
}

static void send_multicast_announcement(int mcast_sockfd) {
    struct sockaddr_in mcast_addr;
    memset(&mcast_addr, 0, sizeof(mcast_addr));
    mcast_addr.sin_family = AF_INET;
    mcast_addr.sin_addr.s_addr = inet_addr(TELEPAD_MULTICAST_GROUP);
    mcast_addr.sin_port = htons(TELEPAD_PORT);

    char buf[256];
    int  len = snprintf(buf, sizeof(buf), "TELEPAD_PONG:%s", g_hostname);
    sendto(mcast_sockfd, buf, len, 0,
           (struct sockaddr *)&mcast_addr, sizeof(mcast_addr));
}

static void send_pairing_intro_resp(int sockfd, struct sockaddr_in *addr,
                                    socklen_t addr_len) {
    uint8_t resp[1 + 32];
    resp[0] = WIRE_PAIRING_INTRO_RESP;
    telepad_noise_get_pubkey(resp + 1);
    sendto(sockfd, (const char *)resp, sizeof(resp), 0,
           (struct sockaddr *)addr, addr_len);
}

static void handle_plaintext(client_ctx_t *c, int sockfd,
                             const uint8_t *pt, int pt_len) {
    if (pt_len < 1) return;
    uint8_t type = pt[0];
    switch (type) {
        case MSG_TYPE_MOUSE_MOVE: {
            if (pt_len >= (int)sizeof(MsgMouseMove)) {
                MsgMouseMove m; memcpy(&m, pt, sizeof(m));
                platform_mouse_move(m.dx, m.dy);
            }
            break;
        }
        case MSG_TYPE_MOUSE_BUTTON: {
            if (pt_len >= (int)sizeof(MsgMouseButton)) {
                MsgMouseButton m; memcpy(&m, pt, sizeof(m));
                platform_mouse_button(m.button, m.pressed);
            }
            break;
        }
        case MSG_TYPE_SCROLL: {
            if (pt_len >= (int)sizeof(MsgScroll)) {
                MsgScroll m; memcpy(&m, pt, sizeof(m));
                platform_scroll(m.delta);
            }
            break;
        }
        case MSG_TYPE_KEY_PRESS: {
            if (pt_len >= (int)sizeof(MsgKey)) {
                MsgKey m; memcpy(&m, pt, sizeof(m));
                platform_key_press(m.keycode, m.mods);
            }
            break;
        }
        case MSG_TYPE_KEY_RELEASE: {
            if (pt_len >= (int)sizeof(MsgKey)) {
                MsgKey m; memcpy(&m, pt, sizeof(m));
                platform_key_release(m.keycode, m.mods);
            }
            break;
        }
        case MSG_TYPE_TEXT_INPUT: {
            if (pt_len >= 3) {
                uint16_t tl = (uint16_t)pt[1] | ((uint16_t)pt[2] << 8);
                if (3 + tl <= pt_len) {
                    platform_type_text((const char *)pt + 3, tl);
                }
            }
            break;
        }
        case MSG_TYPE_MEDIA_CMD: {
            if (pt_len >= (int)sizeof(MsgMedia)) {
                MsgMedia m; memcpy(&m, pt, sizeof(m));
                platform_media(m.action);
            }
            break;
        }
        case MSG_TYPE_VOLUME_CMD: {
            if (pt_len >= (int)sizeof(MsgVolume)) {
                MsgVolume m; memcpy(&m, pt, sizeof(m));
                platform_volume(m.direction);
            }
            break;
        }
        case MSG_TYPE_LOCK_SCREEN: {
            platform_lock();
            break;
        }
        case MSG_TYPE_LAUNCH_ACTION: {
            if (pt_len >= (int)sizeof(MsgLaunch)) {
                MsgLaunch m; memcpy(&m, pt, sizeof(m));
                platform_launch(m.action);
            }
            break;
        }
        case MSG_TYPE_CLIPBOARD_GET: {
            char clip[32 * 1024];
            size_t n = platform_clipboard_read(clip, sizeof(clip));
            uint8_t pt_out[3 + sizeof(clip)];
            pt_out[0] = MSG_TYPE_CLIPBOARD_DATA;
            pt_out[1] = (uint8_t)(n & 0xFF);
            pt_out[2] = (uint8_t)((n >> 8) & 0xFF);
            if (n > 0) memcpy(pt_out + 3, clip, n);

            uint8_t ct[4 + sizeof(clip)];
            int ct_len = telepad_noise_encrypt(&c->noise, pt_out, (int)(3 + n),
                                               ct + 1, (int)sizeof(ct) - 1);
            if (ct_len > 0) {
                ct[0] = WIRE_TRANSPORT;
                sendto(sockfd, (const char *)ct, 1 + ct_len, 0,
                       (struct sockaddr *)&c->addr, c->addr_len);
            }
            break;
        }
        case MSG_TYPE_CLIPBOARD_SET: {
            if (pt_len >= 3) {
                uint16_t tl = (uint16_t)pt[1] | ((uint16_t)pt[2] << 8);
                if (3 + tl <= pt_len) {
                    platform_clipboard_write((const char *)pt + 3, tl);
                }
            }
            break;
        }
        case MSG_TYPE_NOW_PLAYING_Q: {
            platform_now_playing_t np;
            if (platform_now_playing(&np) != 0) break;

            /* Pack reply: type + flags + position(i64) + duration(i64) + 4 strings */
            uint8_t pt_out[2 + 8 + 8 + 4 * (1 + 256)];
            size_t  off = 0;
            pt_out[off++] = MSG_TYPE_NOW_PLAYING;
            pt_out[off++] = (uint8_t)(np.is_playing ? 1 : 0);

            int64_t pos = np.position_ms;
            int64_t dur = np.duration_ms;
            memcpy(pt_out + off, &pos, 8); off += 8;
            memcpy(pt_out + off, &dur, 8); off += 8;

            const char *strs[] = {
                np.title, np.artist, np.album, np.source_app
            };
            for (int i = 0; i < 4; i++) {
                size_t sl = strlen(strs[i]);
                if (sl > 255) sl = 255;
                pt_out[off++] = (uint8_t)sl;
                if (sl) { memcpy(pt_out + off, strs[i], sl); off += sl; }
            }

            uint8_t ct[2048];
            int ct_len = telepad_noise_encrypt(&c->noise, pt_out, (int)off,
                                               ct + 1, (int)sizeof(ct) - 1);
            if (ct_len > 0) {
                ct[0] = WIRE_TRANSPORT;
                sendto(sockfd, (const char *)ct, 1 + ct_len, 0,
                       (struct sockaddr *)&c->addr, c->addr_len);
            }
            break;
        }
        default: break;
    }
}

static void handle_packet(int sockfd, struct sockaddr_in *addr,
                          socklen_t addr_len, uint8_t *buf, int len) {
    if (len < 1) return;
    uint8_t tag = buf[0];
    if (g_verbose) {
        printf("Received packet: tag=0x%02X, len=%d from %s:%d\n",
               tag, len, inet_ntoa(addr->sin_addr), ntohs(addr->sin_port));
    }

    if (tag == WIRE_DISCOVERY_PROBE) {
        if (len >= 1 + TELEPAD_DISCOVERY_MAGIC_LEN &&
            memcmp(buf + 1, TELEPAD_DISCOVERY_MAGIC,
                   TELEPAD_DISCOVERY_MAGIC_LEN) == 0) {
            send_pong(sockfd, addr, addr_len);
        }
        return;
    }

    if (tag == WIRE_PAIRING_INTRO_REQ) {
        send_pairing_intro_resp(sockfd, addr, addr_len);
        return;
    }

    if (tag == WIRE_HANDSHAKE_INIT) {
        /* Check if this client already has a slot (re-handshake). */
        client_ctx_t *existing = find_existing_client(addr, addr_len);
        if (existing) {
            telepad_noise_session_free(&existing->noise);
        }

        /* Attempt handshake in a temporary session — validate before
           allocating a slot so spoofed packets can't evict real clients. */
        telepad_noise_t temp_noise;
        memset(&temp_noise, 0, sizeof(temp_noise));
        uint8_t resp[NOISE_IK_MSG2_LEN];
        int rl = telepad_noise_handshake_respond(
            &temp_noise, buf + 1, len - 1, resp, sizeof(resp));

        if (rl <= 0) {
            if (g_verbose) printf("Handshake failed (error %d)\n", rl);
            telepad_noise_session_free(&temp_noise);
            return;
        }

        /* TOFU: auto-trust new clients (phone-side fingerprint is the gate). */
        if (!pairing_store_is_trusted(temp_noise.client_pubkey)) {
            pairing_store_trust(temp_noise.client_pubkey);
            printf("New client paired and trusted.\n");
        }

        /* Handshake valid — allocate or reuse a slot. */
        client_ctx_t *c = existing ? existing : allocate_client_slot(addr, addr_len);
        if (!c) {
            telepad_noise_session_free(&temp_noise);
            return;
        }
        c->noise = temp_noise;
        c->last_seen = time(NULL);

        printf("Handshake OK from %s:%d\n",
               inet_ntoa(addr->sin_addr), ntohs(addr->sin_port));

        uint8_t out[1 + NOISE_IK_MSG2_LEN];
        out[0] = WIRE_HANDSHAKE_RESP;
        memcpy(out + 1, resp, (size_t)rl);
        sendto(sockfd, (const char *)out, 1 + rl, 0,
               (struct sockaddr *)addr, addr_len);
        return;
    }

    if (tag != WIRE_TRANSPORT) return;

    client_ctx_t *c = find_existing_client(addr, addr_len);
    if (!c || !telepad_noise_ready(&c->noise)) return;

    uint8_t pt[BUF_SIZE];
    int pt_len = telepad_noise_decrypt(&c->noise, buf + 1, len - 1, pt, sizeof(pt));
    if (pt_len < 1) return;

    c->last_seen = time(NULL);
    handle_plaintext(c, sockfd, pt, pt_len);
}

int main(int argc, char *argv[]) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--verbose") == 0 || strcmp(argv[i], "-v") == 0) {
            g_verbose = 1;
        }
    }

    printf("Starting Telepad server on port %d%s\n", TELEPAD_PORT,
           g_verbose ? " (verbose)" : "");

#ifdef _WIN32
    WSADATA wsaData;
    if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
        fprintf(stderr, "WSAStartup failed.\n");
        return 1;
    }
    SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS);
#endif

    if (telepad_noise_init() != 0) {
        fprintf(stderr, "Noise init failed.\n");
        return 1;
    }
    pairing_store_init();
    if (platform_init() != 0) {
        fprintf(stderr, "Platform init failed.\n");
        return 1;
    }
    get_hostname();
    printf("Hostname: %s\n", g_hostname);
    telepad_noise_print_pairing_banner();

    /* Main unicast UDP socket */
    int sockfd;
#ifdef _WIN32
    sockfd = (int)socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (sockfd == INVALID_SOCKET) {
#else
    sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
#endif
        fprintf(stderr, "socket() failed.\n");
        return 1;
    }

#ifdef _WIN32
    int excl = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
               (const char *)&excl, sizeof(excl));
#else
    int reuse = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_REUSEADDR,
               (const char *)&reuse, sizeof(reuse));
#endif

    int rcvbuf = 1024 * 1024;
    setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, (const char *)&rcvbuf, sizeof(rcvbuf));

    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_addr.s_addr = INADDR_ANY;
    server_addr.sin_port = htons(TELEPAD_PORT);

    if (bind(sockfd, (struct sockaddr *)&server_addr, sizeof(server_addr)) < 0) {
        fprintf(stderr, "bind failed on port %d.\n", TELEPAD_PORT);
        return 1;
    }
    printf("Listening on 0.0.0.0:%d\n", TELEPAD_PORT);

    /* Multicast announcement socket */
    int mcast_sockfd;
#ifdef _WIN32
    mcast_sockfd = (int)socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (mcast_sockfd == INVALID_SOCKET) mcast_sockfd = -1;
#else
    mcast_sockfd = socket(AF_INET, SOCK_DGRAM, 0);
#endif

    if (mcast_sockfd >= 0) {
        int r = 1;
        setsockopt(mcast_sockfd, SOL_SOCKET, SO_REUSEADDR,
                   (const char *)&r, sizeof(r));

        struct sockaddr_in mb;
        memset(&mb, 0, sizeof(mb));
        mb.sin_family = AF_INET;
        mb.sin_addr.s_addr = INADDR_ANY;
        mb.sin_port = htons(TELEPAD_PORT);
        if (bind(mcast_sockfd, (struct sockaddr *)&mb, sizeof(mb)) < 0) {
#ifdef _WIN32
            closesocket(mcast_sockfd);
#else
            close(mcast_sockfd);
#endif
            mcast_sockfd = -1;
        } else {
            struct ip_mreq mreq;
            mreq.imr_multiaddr.s_addr = inet_addr(TELEPAD_MULTICAST_GROUP);
            mreq.imr_interface.s_addr = INADDR_ANY;
            if (setsockopt(mcast_sockfd, IPPROTO_IP, IP_ADD_MEMBERSHIP,
                           (const char *)&mreq, sizeof(mreq)) < 0) {
#ifdef _WIN32
                closesocket(mcast_sockfd);
#else
                close(mcast_sockfd);
#endif
                mcast_sockfd = -1;
            } else {
                unsigned char ttl = 1;
                setsockopt(mcast_sockfd, IPPROTO_IP, IP_MULTICAST_TTL,
                           (const char *)&ttl, sizeof(ttl));
                unsigned char loop = 0;
                setsockopt(mcast_sockfd, IPPROTO_IP, IP_MULTICAST_LOOP,
                           (const char *)&loop, sizeof(loop));
                printf("Joined multicast %s\n", TELEPAD_MULTICAST_GROUP);
            }
        }
    }

    uint8_t buffer[BUF_SIZE];
    struct sockaddr_in client_addr;
    socklen_t          client_len;
    time_t             last_announce = time(NULL);

    while (1) {
        fd_set readfds;
        FD_ZERO(&readfds);
        FD_SET((unsigned)sockfd, &readfds);
        int maxfd = sockfd;
        if (mcast_sockfd >= 0) {
            FD_SET((unsigned)mcast_sockfd, &readfds);
            if (mcast_sockfd > maxfd) maxfd = mcast_sockfd;
        }

        time_t now = time(NULL);
        long   remaining = ANNOUNCE_INTERVAL_SEC - (now - last_announce);
        if (remaining < 0) remaining = 0;
        struct timeval tv;
        tv.tv_sec = remaining;
        tv.tv_usec = 0;

        int ready = select(maxfd + 1, &readfds, NULL, NULL, &tv);
        if (ready < 0) continue;
        if (ready == 0) {
            if (mcast_sockfd >= 0) {
                send_multicast_announcement(mcast_sockfd);
                last_announce = time(NULL);
            }
            continue;
        }

        if (FD_ISSET((unsigned)sockfd, &readfds)) {
            client_len = sizeof(client_addr);
            int rl = (int)recvfrom(sockfd, (char *)buffer, BUF_SIZE, 0,
                                   (struct sockaddr *)&client_addr, &client_len);
            if (rl > 0) handle_packet(sockfd, &client_addr, client_len, buffer, rl);
        }
        if (mcast_sockfd >= 0 && FD_ISSET((unsigned)mcast_sockfd, &readfds)) {
            client_len = sizeof(client_addr);
            int rl = (int)recvfrom(mcast_sockfd, (char *)buffer, BUF_SIZE, 0,
                                   (struct sockaddr *)&client_addr, &client_len);
            if (rl > 0 && buffer[0] == WIRE_DISCOVERY_PROBE &&
                rl >= 1 + TELEPAD_DISCOVERY_MAGIC_LEN &&
                memcmp(buffer + 1, TELEPAD_DISCOVERY_MAGIC,
                       TELEPAD_DISCOVERY_MAGIC_LEN) == 0) {
                send_pong(sockfd, &client_addr, client_len);
            }
        }
    }

    pairing_store_cleanup();
    telepad_noise_cleanup();
    platform_cleanup();
#ifdef _WIN32
    closesocket((unsigned)sockfd);
    if (mcast_sockfd >= 0) closesocket((unsigned)mcast_sockfd);
    WSACleanup();
#else
    close(sockfd);
    if (mcast_sockfd >= 0) close(mcast_sockfd);
#endif
    return 0;
}
