#include <jni.h>
#include <string>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <android/log.h>
#include "protocol.h"

#define LOG_TAG "TelepadNDK"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int sockfd = -1;
static struct sockaddr_in server_addr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeConnect(
        JNIEnv* env,
        jobject /* this */,
        jstring ip_address,
        jint port) {

    if (sockfd >= 0) {
        close(sockfd);
    }

    sockfd = socket(AF_INET, SOCK_DGRAM, 0);
    if (sockfd < 0) {
        LOGE("Failed to create UDP socket");
        return JNI_FALSE;
    }

    // Low-Level Native Socket Tuning for Ultra-Low Latency
    int broadcast = 1;
    setsockopt(sockfd, SOL_SOCKET, SO_BROADCAST, &broadcast, sizeof(broadcast));

    int priority = 6; // High priority interactive network traffic
    setsockopt(sockfd, SOL_SOCKET, SO_PRIORITY, &priority, sizeof(priority));

    int tos = 0x10; // IPTOS_LOWDELAY (Low Latency / Expedited Forwarding)
    setsockopt(sockfd, IPPROTO_IP, IP_TOS, &tos, sizeof(tos));

    int sndbuf = 64 * 1024;
    setsockopt(sockfd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    const char *ip_str = env->GetStringUTFChars(ip_address, nullptr);

    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(port);

    if (inet_pton(AF_INET, ip_str, &server_addr.sin_addr) <= 0) {
        LOGE("Invalid address/ Address not supported: %s", ip_str);
        env->ReleaseStringUTFChars(ip_address, ip_str);
        close(sockfd);
        sockfd = -1;
        return JNI_FALSE;
    }

    env->ReleaseStringUTFChars(ip_address, ip_str);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeDisconnect(
        JNIEnv* env,
        jobject /* this */) {
    if (sockfd >= 0) {
        close(sockfd);
        sockfd = -1;
    }
}

static inline void send_packet(const void* buf, size_t len) {
    if (sockfd >= 0) {
        sendto(sockfd, buf, len, MSG_DONTWAIT, (struct sockaddr *)&server_addr, sizeof(server_addr));
    }
}

// ── Native Input API ────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendMouseMove(
        JNIEnv* env, jobject, jint dx, jint dy) {
    MsgMouseMove msg;
    msg.type = MSG_TYPE_MOUSE_MOVE;
    msg.dx = static_cast<int16_t>(dx);
    msg.dy = static_cast<int16_t>(dy);
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendMouseButton(
        JNIEnv* env, jobject, jint button, jboolean pressed) {
    MsgMouseButton msg;
    msg.type = MSG_TYPE_MOUSE_BUTTON;
    msg.button = static_cast<uint8_t>(button);
    msg.pressed = pressed ? 1 : 0;
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendScroll(
        JNIEnv* env, jobject, jint delta) {
    MsgScroll msg;
    msg.type = MSG_TYPE_SCROLL;
    msg.delta = static_cast<int16_t>(delta);
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendKey(
        JNIEnv* env, jobject, jboolean pressed, jint keycode, jint modifiers) {
    MsgKey msg;
    msg.type = pressed ? MSG_TYPE_KEY_PRESS : MSG_TYPE_KEY_RELEASE;
    msg.keycode = static_cast<uint16_t>(keycode);
    msg.modifiers = static_cast<uint8_t>(modifiers);
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendMedia(
        JNIEnv* env, jobject, jint action) {
    MsgMedia msg;
    msg.type = MSG_TYPE_MEDIA_CMD;
    msg.action = static_cast<uint8_t>(action);
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendVolume(
        JNIEnv* env, jobject, jint direction) {
    MsgVolume msg;
    msg.type = MSG_TYPE_VOLUME_CMD;
    msg.direction = static_cast<uint8_t>(direction);
    send_packet(&msg, sizeof(msg));
}

extern "C" JNIEXPORT void JNICALL
Java_com_omsingh_telepad_core_wifi_UdpSender_nativeSendLockScreen(
        JNIEnv* env, jobject) {
    MsgHeader msg;
    msg.type = MSG_TYPE_LOCK_SCREEN;
    send_packet(&msg, sizeof(msg));
}
