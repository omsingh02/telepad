#ifndef TELEPAD_PLATFORM_H
#define TELEPAD_PLATFORM_H

#include <stdint.h>
#include <stddef.h>

/* Initialise the platform input synthesis subsystem. Returns 0 on success. */
int  platform_init(void);
void platform_cleanup(void);

/* Mouse */
void platform_mouse_move(int16_t dx, int16_t dy);
void platform_mouse_button(uint8_t button, uint8_t pressed);
void platform_scroll(int16_t delta);

/* Keyboard */
void platform_key_press(uint16_t hid_usage, uint8_t modifiers);
void platform_key_release(uint16_t hid_usage, uint8_t modifiers);
void platform_type_text(const char *utf8, uint16_t len);

/* System */
void platform_media(uint8_t action);
void platform_volume(uint8_t direction);
void platform_lock(void);
void platform_launch(uint8_t action);

/* Clipboard */
size_t platform_clipboard_read(char *out, size_t max);
void   platform_clipboard_write(const char *utf8, size_t len);

/* Now-playing snapshot (packed into the reply payload by main.c). */
typedef struct {
    uint8_t  is_playing;
    int64_t  position_ms;
    int64_t  duration_ms;
    char     title[256];
    char     artist[256];
    char     album[256];
    char     source_app[128];
} platform_now_playing_t;

/* Fill out *np with the currently-playing media. Returns 0 on success,
   -1 if nothing is playing (in which case np contents are unspecified). */
int platform_now_playing(platform_now_playing_t *np);

#endif /* TELEPAD_PLATFORM_H */
