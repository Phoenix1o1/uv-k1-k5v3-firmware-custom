/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

/*
 * Channels - browse the memory channels by scan list (the groups CHIRP
 * writes) and tune one. Needs API level 4 (mr_list, mr_read, list_name,
 * mr_stage).
 *
 *   Groups view    ALL, each scan list that holds a channel, NO LIST,
 *                  with their channel counts
 *     UP/DOWN      move (held: repeat)
 *     MENU         open the group
 *     EXIT         quit
 *   Channels view  the group's channels: name (or number) and RX frequency;
 *                  the bottom line details the selected one
 *     UP/DOWN      move (held: repeat)
 *     MENU         tune it on the current VFO (memory mode) and quit
 *     EXIT         back to the groups
 *
 * Nothing is cached per channel (1024 memories do not fit the 4 KiB
 * overlay): the cursor is a channel number, and moving it asks mr_list
 * about the memories in between until one of the group is found.
 */

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include "../app_api.h"

void *memset(void *d, int c, size_t n)   /* the compiler may emit calls to it */
{
    uint8_t *p = d;
    while (n--)
        *p++ = (uint8_t)c;
    return d;
}

/* Groups: G_ALL, scan lists 1..24, G_NONE. */
#define G_ALL     0u
#define G_NONE    (APP_MR_LISTS + 1u)
#define G_COUNT   (APP_MR_LISTS + 2u)
#define NO_CH     0xFFFFu

/* Screen: title on line 0, ROWS list rows from line 1, detail on line 6. */
#define ROWS        5u
#define FIRST_ROW   1u
#define DETAIL_LINE 6u
#define CHAR_W      7u                  /* small font, spacing included */

#define INPUT_TICK_MS       15u
#define NAV_REPEAT_DELAY_MS 300u
#define NAV_REPEAT_MS       60u

static const app_api_t *A;

static struct {
    uint16_t cnt[G_COUNT];      /* channels per group                   */
    uint16_t nmr;               /* number of memories in this firmware  */
    uint8_t  gids[G_COUNT];     /* the groups shown, in order           */
    uint8_t  ngroups;
    uint8_t  gsel, gtop;        /* groups view: selected and first row  */
    bool     in_group;          /* false: groups view, true: channels   */
    uint8_t  group;             /* channels view: its group             */
    uint16_t sel, top;          /* selected and first channel (numbers) */
    uint16_t idx;               /* position of sel in the group, for 2/16 */
} g;

/* ---- text helpers (no libc in an overlay app) ---- */

static uint8_t slen(const char *s)
{
    uint8_t n = 0;
    while (s[n])
        n++;
    return n;
}

static char *put_s(char *o, const char *s)
{
    while (*s)
        *o++ = *s++;
    return o;
}

/* Frequency in 10 Hz units as "145.5125": MHz, then 3 to 5 decimals
 * (trailing zeros dropped past the third, so 6.25 kHz steps still show). */
static char *put_freq(char *o, uint32_t f)
{
    const uint64_t qr = A->uidivmod(f, 100000u);    /* MHz | remainder */
    uint32_t r = (uint32_t)(qr >> 32);
    char d[5];
    for (int8_t i = 4; i >= 0; i--) {
        const uint64_t x = A->uidivmod(r, 10u);
        d[i] = (char)('0' + (uint32_t)(x >> 32));
        r = (uint32_t)x;
    }
    uint8_t n = 5;
    while (n > 3u && d[n - 1u] == '0')
        n--;
    o = A->append_u32(o, (uint32_t)qr);
    *o++ = '.';
    for (uint8_t i = 0; i < n; i++)
        *o++ = d[i];
    return o;
}

/* "ALL", "NO LIST", "01 RPT", or "LIST 05" for a list without a name. */
static char *put_group(char *o, uint8_t grp)
{
    char name[5];
    if (grp == G_ALL)
        return put_s(o, "ALL");
    if (grp == G_NONE)
        return put_s(o, "NO LIST");
    A->list_name(grp, name);
    if (!name[0])
        o = put_s(o, "LIST ");
    if (grp < 10u)
        *o++ = '0';
    o = A->append_u32(o, grp);
    if (name[0]) {
        *o++ = ' ';
        o = put_s(o, name);
    }
    return o;
}

/* ---- groups and channels ---- */

/* A channel in every list (APP_MR_EVERY) belongs to each list group. */
static bool in_group(uint16_t ch, uint8_t grp)
{
    const int16_t l = A->mr_list(ch);
    if (l < 0)                          /* empty memory */
        return false;
    if (grp == G_ALL)
        return true;
    if (grp == G_NONE)
        return l == 0;
    return l == grp || l == APP_MR_EVERY;
}

/* The next channel of the group after (dir > 0) or before (dir < 0) ch,
 * NO_CH when there is none. step(NO_CH, 1, grp) is the group's first. */
static uint16_t step(uint16_t ch, int8_t dir, uint8_t grp)
{
    int32_t c = (ch == NO_CH) ? -1 : (int32_t)ch;
    for (c += dir; c >= 0 && c < (int32_t)g.nmr; c += dir)
        if (in_group((uint16_t)c, grp))
            return (uint16_t)c;
    return NO_CH;
}

/* Count the channels of each group once, at launch. A list is shown when it
 * holds a channel of its own: a channel in every list counts in each, but
 * does not make all 24 lists appear. */
static void count_groups(void)
{
    uint32_t shown = 1u << G_ALL;
    uint16_t ch = 0;
    int16_t l;
    while ((l = A->mr_list(ch)) != APP_MR_END) {
        if (l == APP_MR_EVERY) {
            g.cnt[G_ALL]++;
            for (uint8_t i = 1; i <= APP_MR_LISTS; i++)
                g.cnt[i]++;
        } else if (l >= 0) {
            const uint8_t grp = l ? (uint8_t)l : G_NONE;
            g.cnt[G_ALL]++;
            g.cnt[grp]++;
            shown |= 1u << grp;
        }
        ch++;
    }
    g.nmr = ch;

    for (uint8_t i = 0; i < G_COUNT; i++)
        if (shown & (1u << i))
            g.gids[g.ngroups++] = i;
}

/* ---- drawing ---- */

/* Selection bar over a list row (and the row's top pixel line). */
static void invert_row(uint8_t line)
{
    for (uint8_t x = 0; x < 128u; x++) {
        A->fb[line][x] ^= 0xFFu;
        A->fb[line - 1u][x] ^= 0x80u;
    }
}

/* One row: left text from x = 1, right text ending at x = 127. The left
 * text is cut so the two stay one character apart. */
static void row(uint8_t line, char *left, const char *right, bool bold)
{
    const uint8_t rn = slen(right);
    uint8_t max = (uint8_t)A->uidivmod(126u - rn * CHAR_W, CHAR_W);
    if (rn)
        max--;
    if (slen(left) > max)
        left[max] = '\0';
    if (bold)
        A->print_bold(left, 1u, 0u, line);
    else
        A->print_normal(left, 1u, 0u, line);
    if (rn)
        A->print_normal(right, (uint8_t)(128u - rn * CHAR_W), 0u, line);
}

static void draw_status(void)
{
    static const char title[] = "CHANNELS";
    A->status_clear();
    A->print_inverse(title, 2u, 0u, true, true, (uint8_t)(2u + (sizeof(title) - 1u) * 4u));
    A->draw_battery();
    A->blit_status();
}

static void draw_groups(void)
{
    char l[24], r[8], *o;

    o = put_s(l, "GROUPS");
    *o = '\0';
    o = A->append_u32(r, g.ngroups - 1u);   /* ALL is not counted */
    *o = '\0';
    row(0u, l, r, true);

    for (uint8_t i = 0; i < ROWS && g.gtop + i < g.ngroups; i++) {
        const uint8_t grp = g.gids[g.gtop + i];
        o = put_group(l, grp);
        *o = '\0';
        o = A->append_u32(r, g.cnt[grp]);
        *o = '\0';
        row(FIRST_ROW + i, l, r, false);
        if (g.gtop + i == g.gsel)
            invert_row(FIRST_ROW + i);
    }
    A->print_tiny("MENU OPEN   EXIT QUIT", 1u, DETAIL_LINE * 8u + 2u, false, true);
}

/* Bottom line: "CH 12  FM W  -0.600  CTCSS" for the selected channel. */
static void draw_detail(uint16_t ch)
{
    static const char mods[] = "FMAMUSB";   /* modulation 0 FM, 1 AM, 2 USB */
    app_beam_channel_t c;
    char t[40], *o = t;

    if (!A->mr_read(ch, &c))
        return;
    o = put_s(o, "CH ");
    o = A->append_u32(o, ch + 1u);          /* numbered from 1, as on the radio */
    o = put_s(o, "  ");
    if (c.modulation <= 2u) {
        const char *m = mods + c.modulation * 2u;
        *o++ = m[0];
        *o++ = m[1];
        if (c.modulation == 2u)
            *o++ = m[2];
        *o++ = ' ';
    }
    *o++ = c.channel_bandwidth ? 'N' : 'W';
    if (c.tx_offset_direction == 1u || c.tx_offset_direction == 2u) {   /* + / - */
        o = put_s(o, "  ");
        *o++ = c.tx_offset_direction == 1u ? '+' : '-';
        o = put_freq(o, c.tx_offset_frequency);
    }
    if (c.rx_codetype || c.tx_codetype) {   /* 1 CTCSS, 2/3 DCS */
        o = put_s(o, "  ");
        o = put_s(o, c.tx_codetype >= 2u || c.rx_codetype >= 2u ? "DCS" : "CTCSS");
    }
    *o = '\0';
    A->print_tiny(t, 1u, DETAIL_LINE * 8u + 2u, false, true);
}

static void draw_channels(void)
{
    char l[24], r[16], *o;

    o = put_group(l, g.group);
    *o = '\0';
    o = A->append_u32(r, g.idx + 1u);
    *o++ = '/';
    o = A->append_u32(o, g.cnt[g.group]);
    *o = '\0';
    row(0u, l, r, true);

    uint16_t ch = g.top;
    for (uint8_t i = 0; i < ROWS && ch != NO_CH; i++) {
        app_beam_channel_t c;
        if (A->mr_read(ch, &c)) {
            if (c.name[0]) {
                o = put_s(l, c.name);
            } else {
                o = put_s(l, "CH-");
                o = A->append_u32(o, ch + 1u);
            }
            *o = '\0';
            o = put_freq(r, c.rx_frequency);
            *o = '\0';
            row(FIRST_ROW + i, l, r, false);
            if (ch == g.sel)
                invert_row(FIRST_ROW + i);
        }
        ch = step(ch, 1, g.group);
    }
    draw_detail(g.sel);
}

static void draw(void)
{
    A->display_clear();
    if (g.in_group)
        draw_channels();
    else
        draw_groups();
    A->blit_full();
}

/* ---- navigation ---- */

static void open_group(void)
{
    g.group = g.gids[g.gsel];
    g.in_group = true;
    g.sel = g.top = step(NO_CH, 1, g.group);
    g.idx = 0;
}

static void navigate(int8_t dir)
{
    if (!g.in_group) {
        if (dir > 0 && g.gsel + 1u < g.ngroups)
            g.gsel++;
        else if (dir < 0 && g.gsel)
            g.gsel--;
        else
            return;
        if (g.gsel < g.gtop)
            g.gtop = g.gsel;
        else if (g.gsel >= g.gtop + ROWS)
            g.gtop = (uint8_t)(g.gsel - ROWS + 1u);
        return;
    }

    const uint16_t n = step(g.sel, dir, g.group);
    if (n == NO_CH)                     /* first or last of the group */
        return;
    g.sel = n;
    g.idx += dir;
    if (dir < 0 && g.sel < g.top) {
        g.top = g.sel;                  /* scrolled above the first row */
    } else if (dir > 0) {
        /* below the last row? find the channel on the last row */
        uint16_t last = g.top;
        for (uint8_t i = 1; i < ROWS; i++) {
            const uint16_t p = step(last, 1, g.group);
            if (p == NO_CH)
                break;
            last = p;
        }
        if (g.sel > last)
            g.top = step(g.top, 1, g.group);
    }
}

/* Show a message until EXIT. */
static void message(const char *l1, const char *l2)
{
    A->display_clear();
    A->print_bold(l1, 0u, 127u, 2u);
    A->print_normal(l2, 0u, 127u, 4u);
    A->blit_full();
    while (A->get_key() != APP_KEY_EXIT) {
        A->delay_ms(INPUT_TICK_MS);
        A->backlight_update();
    }
}

__attribute__((section(".text.entry"), used))
void app_main(const app_api_t *api)
{
    A = api;
    A->backlight_on();
    draw_status();

    /* The Apps menu already refuses this app below API level 4; the size
     * check also covers a level 4 table without these services. */
    if (api->api_level < 4u ||
        api->api_size < offsetof(app_api_t, mr_stage) + sizeof(api->mr_stage)) {
        message("NEEDS API 4", "UPDATE FIRMWARE");
        return;
    }

    count_groups();
    if (!g.cnt[G_ALL]) {
        message("NO CHANNELS", "PROGRAM WITH CHIRP");
        return;
    }

    /* Key loop: act on a new key, repeat UP/DOWN while held. */
    uint8_t previous = APP_KEY_INVALID;
    uint16_t repeat_ms = 0;
    uint8_t battery_ticks = 0;
    bool dirty = true;
    for (;;) {
        const uint8_t key = A->get_key();
        const int8_t dir = (key == APP_KEY_DOWN) - (key == APP_KEY_UP);

        if (key == APP_KEY_SAVER) {             /* screen saver on: no drawing */
            previous = APP_KEY_INVALID;
        } else if (key == APP_KEY_WAKE) {       /* screen saver off: redraw */
            previous = APP_KEY_INVALID;
            draw_status();
            dirty = true;
        } else if (key == APP_KEY_INVALID) {
            previous = key;
        } else if (key != previous) {           /* new key */
            previous = key;
            repeat_ms = NAV_REPEAT_DELAY_MS;
            if (dir) {
                navigate(dir);
            } else if (key == APP_KEY_MENU) {
                if (!g.in_group)
                    open_group();
                else if (A->mr_stage(g.sel))
                    return;                     /* the loader tunes it */
                else
                    A->play_tone(500u, 60u);
            } else if (key == APP_KEY_EXIT) {
                if (!g.in_group)
                    return;
                g.in_group = false;
            }
            dirty = true;
        } else if (dir) {                       /* UP/DOWN held */
            if (repeat_ms > INPUT_TICK_MS) {
                repeat_ms -= INPUT_TICK_MS;
            } else {
                navigate(dir);
                repeat_ms = NAV_REPEAT_MS;
                dirty = true;
            }
        }

        if (dirty && key != APP_KEY_SAVER) {
            draw();
            dirty = false;
        }
        A->delay_ms(INPUT_TICK_MS);
        if (++battery_ticks >= 200u) {          /* battery icon, every ~3 s */
            battery_ticks = 0;
            A->battery_sample();
            if (key != APP_KEY_SAVER)
                draw_status();
        }
        A->backlight_update();
    }
}
