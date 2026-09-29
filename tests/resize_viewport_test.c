/* Production view mapping shared by rendering, pointer input and IME cursor
 * geometry. Exercise asynchronous configure/ack/buffer-commit ordering. */
#include "../services/waylandbridge/awl_viewport.c"
#include <assert.h>
#include <fcntl.h>
#include <sys/eventfd.h>
#include <unistd.h>

int __android_log_print(int priority, const char* tag, const char* format, ...) {
    (void)priority; (void)tag; (void)format;
    return 0;
}

struct awl_server g_srv;

static void mapping(struct awl_surface* s, double ex, double ey) {
    double sx, sy, ox, oy;
    awl_surface_view_map(s, &sx, &sy, &ox, &oy);
    assert(fabs(sx-ex) < .00001 && fabs(sy-ey) < .00001);
    assert(ox == 0. && oy == 0.);
}

static void frame_sampling(void) {
    struct awl_surface s = {0};
    s.content_generation = 2;
    s.content_width = 3264; s.content_height = 1194;
    s.vp_dst_w = 1632; s.vp_dst_h = 597;
    struct awl_bq_buffer old = {0};
    old.content_generation = 1;
    old.width = 3264; old.height = 2560;
    old.logical_w = 1632; old.logical_h = 597;
    old.su = 1; old.sv = 1194.f / 2560.f;
    awl_layer_info_t layer = { .surface_id = 42, .x = 7, .y = 9 };

    // Chromium trims its oversized resize buffer one second after settling.
    // The new viewport is whole-image, but its frame is not ready yet. The
    // previous padded frame must retain its crop, not expose the black tail.
    frame_view_locked(&s, &old, &layer);
    assert(layer.w == 1632 && layer.h == 597);
    assert(fabs(layer.sv * old.height - 1194) < .001);
    assert(layer.surface_id == 42 && layer.x == 7 && layer.y == 9);

    struct awl_bq_buffer fresh = old;
    fresh.content_generation = 2; fresh.height = 1194; fresh.sv = 1;
    frame_view_locked(&s, &fresh, &layer);
    assert(layer.sv == 1);

    // Legal metadata-only commit updates the current buffer; it must not
    // rewrite older frame metadata (including transform and logical size).
    s.vp_dst_h = 300; s.vp_has_src = 1;
    s.vp_sw = 3264; s.vp_sh = 600; s.buf_transform = 2;
    frame_view_locked(&s, &fresh, &layer);
    assert(layer.h == 300 && layer.transform == 2);
    assert(fabs(layer.sv * fresh.height - 600) < .001);
    frame_view_locked(&s, &old, &layer);
    assert(layer.h == 597 && layer.transform == 0);
    assert(fabs(layer.sv * old.height - 1194) < .001);
    frame_view_locked(NULL, &old, &layer); // surface died after frame ref
    assert(layer.h == 597);

    // Drive the actual mailbox, with a pollable gate standing in for the
    // new frame's acquire fence. This is the device's delayed-buffer order.
    struct awl_bufferqueue* q = awl_bufferqueue_create(NULL, NULL);
    int gate = eventfd(0, EFD_CLOEXEC);
    assert(q && gate >= 0);
    old.dmabuf_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    old.acquire_fd = old.release_fd = -1;
    fresh.dmabuf_fd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    fresh.acquire_fd = dup(gate); fresh.release_fd = -1;
    assert(old.dmabuf_fd >= 0 && fresh.dmabuf_fd >= 0 && fresh.acquire_fd >= 0);
    assert(awl_bufferqueue_push(q, &old) && awl_bufferqueue_push(q, &fresh));
    s.vp_has_src = 0; s.vp_dst_h = 597; s.buf_transform = 0;
    awl_bufferqueue_lock(q);
    assert(awl_bufferqueue_drain(q) == 0);
    struct awl_bq_buffer* held = awl_bufferqueue_gethead(q, 0);
    assert(held && held->content_generation == 1);
    frame_view_locked(&s, held, &layer);
    assert(fabs(layer.sv * held->height - 1194) < .001);
    uint64_t ready = 1;
    assert(write(gate, &ready, sizeof(ready)) == sizeof(ready));
    assert(awl_bufferqueue_drain(q) == 1);
    struct awl_bq_buffer* next = awl_bufferqueue_gethead(q, 0);
    assert(next && next->content_generation == 2);
    frame_view_locked(&s, next, &layer);
    assert(layer.sv == 1 && layer.h == 597);
    frame_view_locked(&s, held, &layer); // ref stays valid after mailbox moved
    assert(fabs(layer.sv * held->height - 1194) < .001);
    awl_bufferqueue_flush(q);
    awl_bufferqueue_unlock(q);
    awl_bufferqueue_put(held, -1);
    awl_bufferqueue_put(next, -1);
    awl_bufferqueue_unref(q);
    close(gate);
    puts("PASS delayed acquire fence/old head/new head/retained frame metadata");
    puts("PASS padded frame/new viewport/metadata-only commit/retired surface sampling");
}

int main(void) {
    frame_sampling();
    g_srv.zoom_pct = 200;
    struct awl_surface s = {0};
    s.role = AWL_ROLE_TOPLEVEL; s.geom_valid = true; s.configured = true;
    s.geom_w = 1600; s.geom_h = 1068;
    s.phys_w = 3200; s.phys_h = 2136;
    s.u.xdg.conf_w = 1600; s.u.xdg.conf_h = 1068;
    s.u.xdg.conf_serial = s.u.xdg.ack_serial = 10;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.);

    // IME changes the output before the responsive client can render. Keep
    // text scale and input coordinates stable instead of squashing the frame.
    s.phys_h = 1108; s.u.xdg.conf_h = 554; s.u.xdg.conf_serial = 11;
    mapping(&s, 2., 2.);
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.); // old response
    s.u.xdg.ack_serial = 11;
    awl_surface_commit_view(&s, 0); mapping(&s, 2., 2.); // empty ack commit
    s.geom_h = 554;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.);

    // Another animation frame supersedes an acknowledged resize. Neither
    // its delayed buffer nor the latest empty ack changes the scale.
    s.phys_h = 1500; s.u.xdg.conf_h = 750; s.u.xdg.conf_serial = 12;
    s.u.xdg.ack_serial = 12;
    s.phys_h = 1800; s.u.xdg.conf_h = 900; s.u.xdg.conf_serial = 13;
    s.geom_h = 750;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.);
    s.u.xdg.ack_serial = 13;
    awl_surface_commit_view(&s, 0); mapping(&s, 2., 2.);
    s.geom_h = 900;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.);

    // A client that actually commits a different size after the latest ack
    // still gets the configured placement mode (no app-specific heuristic).
    s.geom_h = 600;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 3.);
    s.geom_h = 900;
    awl_surface_commit_view(&s, 1); mapping(&s, 2., 2.);
    g_srv.zoom_pct = 150; mapping(&s, 1.5, 1.5);

    // Xwayland fixed-size rendering remains on its existing mapping path.
    s.role = AWL_ROLE_XWAYLAND;
    s.geom_h = 600; mapping(&s, 2., 3.);
    puts("PASS resize/old ack/empty ack/superseded response/fixed client/zoom/Xwayland mapping");
}
