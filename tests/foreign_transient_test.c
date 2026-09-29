/* Real Wayland resources, separate clients, and production parent/foreign
 * code. No renderer or Android task is needed to test relation lifetime. */
#include "../services/waylandbridge/awl_foreign.c"
#include <assert.h>
#include <sys/socket.h>
#include <unistd.h>
struct awl_server g_srv;
double awl_zoom_scale(void) { return 2.; }
struct awl_surface* awl_surface_by_id(uint64_t id) {
    struct awl_surface* s;
    wl_list_for_each(s, &g_srv.surfaces, link) if (s->id == id) return s;
    return NULL;
}
int __android_log_print(int p, const char* tag, const char* fmt, ...) { return 0; }
static void surface_init(struct awl_surface* s, uint64_t id, struct wl_client* c) {
    memset(s, 0, sizeof(*s));
    s->id = id; s->role = AWL_ROLE_TOPLEVEL;
    pthread_mutex_init(&s->ev_lock, NULL);
    s->resource = wl_resource_create(c, &wl_surface_interface, 1, 2);
    wl_resource_set_user_data(s->resource, s);
    wl_list_insert(&g_srv.surfaces, &s->link);
}
int main(void) {
    pthread_rwlock_init(&g_srv.rwl, NULL);
    wl_list_init(&g_srv.surfaces);
    g_srv.display = wl_display_create(); awl_foreign_setup();
    int a[2], b[2]; assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, a));
    assert(!socketpair(AF_UNIX, SOCK_STREAM, 0, b));
    struct wl_client* ca = wl_client_create(g_srv.display, a[0]);
    struct wl_client* cb = wl_client_create(g_srv.display, b[0]);
    struct awl_surface parent, child;
    surface_init(&parent, 1, ca); surface_init(&child, 2, cb);
    struct wl_resource* exporter = wl_resource_create(ca, &zxdg_exporter_v2_interface, 1, 3);
    struct wl_resource* importer = wl_resource_create(cb, &zxdg_importer_v2_interface, 1, 3);
    export_toplevel(ca, exporter, 4, parent.resource);
    struct exported* e = wl_container_of(exports.next, e, link);
    assert(strlen(e->handle) == 64);
    import_toplevel(cb, importer, 4, e->handle);
    struct imported* i = wl_container_of(imports.next, i, link);
    assert(i->source == e);
    set_parent_of(cb, i->res, child.resource);
    assert(child.u.xdg.parent_id == parent.id);
    assert(!awl_xdg_set_parent(&parent, child.id, NULL)); // cycle rejected
    assert(!awl_xdg_set_parent(&child, child.id, NULL));
    child.u.xdg.natural_w = 601; child.u.xdg.natural_h = 282;
    child.u.xdg.max_w = 550; child.u.xdg.max_h = 192;
    awl_presentation_t p; awl_window_presentation(child.id, &p);
    assert(p.parent == 1 && p.dialog && p.width == 1100 && p.height == 384);
    wl_resource_destroy(i->res);
    assert(child.u.xdg.parent_id == 0 && child.u.xdg.parent_owner == NULL);
    import_toplevel(cb, importer, 5, e->handle);
    i = wl_container_of(imports.next, i, link);
    set_parent_of(cb, i->res, child.resource);
    awl_foreign_surface_gone(&parent);
    assert(!i->source && !child.u.xdg.parent_id);
    import_toplevel(cb, importer, 6, "missing-handle");
    struct imported* invalid = wl_container_of(imports.next, invalid, link);
    assert(!invalid->source);
    set_parent_of(cb, invalid->res, child.resource); // inert after destroyed
    assert(!child.u.xdg.parent_id);
    wl_resource_destroy(e->res);
    wl_client_destroy(cb); wl_client_destroy(ca);
    wl_display_destroy(g_srv.display); close(a[1]); close(b[1]);
    puts("PASS foreign imports, parent cycles, scaled bounds, import/export/surface destruction, invalid handle");
}
