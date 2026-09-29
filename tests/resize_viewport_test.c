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

int main(void) {
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
