/* Production view mapping shared by rendering, pointer input and IME cursor
 * geometry. Exercise asynchronous configure/ack/buffer-commit ordering. */
#include "../services/waylandbridge/awl_viewport.c"
#include <assert.h>

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
