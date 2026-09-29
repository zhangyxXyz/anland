/* xdg-foreign-v2: Electron/Chromium and GTK may use separate Wayland
 * connections in the same process. A dialog's real transient parent is
 * conveyed by an exported capability, never by its title, app id or PID.
 *
 * Per-client dispatch threads run concurrently. Lock order is foreign_lock
 * -> rwl -> surface ev_lock. Surface/role teardown revokes handles BEFORE
 * taking rwl; resource destruction stays on its owning dispatch thread. */
#include "awl_internal.h"
#include "xdg-foreign-unstable-v2-server-protocol.h"
#include <sys/random.h>
#include <string.h>
#include <stdlib.h>

struct exported {
    struct wl_resource* res;
    struct wl_list link;
    uint64_t surface;
    char handle[65];
};
struct imported {
    struct wl_resource* res;
    struct wl_list link;
    struct exported* source;
};
static struct wl_list exports, imports;
static pthread_mutex_t foreign_lock = PTHREAD_MUTEX_INITIALIZER;

static void destroy_request(struct wl_client* c, struct wl_resource* r) {
    wl_resource_destroy(r);
}

/* foreign_lock held. Only clear relations still owned by this import;
 * a later set_parent or a different import supersedes the old one. */
static void clear_relations(struct imported* owner) {
    pthread_rwlock_wrlock(&g_srv.rwl);
    struct awl_surface* s;
    wl_list_for_each(s, &g_srv.surfaces, link) {
        pthread_mutex_lock(&s->ev_lock);
        if (s->role == AWL_ROLE_TOPLEVEL && s->u.xdg.parent_owner == owner) {
            s->u.xdg.parent_id = 0;
            s->u.xdg.parent_owner = NULL;
        }
        pthread_mutex_unlock(&s->ev_lock);
    }
    pthread_rwlock_unlock(&g_srv.rwl);
}

static void revoke_export(struct exported* e) {
    struct imported* i;
    wl_list_for_each(i, &imports, link) {
        if (i->source != e) continue;
        clear_relations(i);
        i->source = NULL;
        zxdg_imported_v2_send_destroyed(i->res);
        wl_client_flush(wl_resource_get_client(i->res));
    }
    e->surface = 0;
}

static void exported_destroy(struct wl_resource* r) {
    struct exported* e = wl_resource_get_user_data(r);
    if (!e) return;
    pthread_mutex_lock(&foreign_lock);
    revoke_export(e);
    wl_list_remove(&e->link);
    pthread_mutex_unlock(&foreign_lock);
    free(e);
}
static void imported_destroy(struct wl_resource* r) {
    struct imported* i = wl_resource_get_user_data(r);
    if (!i) return;
    pthread_mutex_lock(&foreign_lock);
    clear_relations(i);
    wl_list_remove(&i->link);
    pthread_mutex_unlock(&foreign_lock);
    free(i);
}

static void set_parent_of(struct wl_client* c, struct wl_resource* r, struct wl_resource* surface) {
    struct imported* i = wl_resource_get_user_data(r);
    struct awl_surface* s = wl_resource_get_user_data(surface);
    if (!s || s->role != AWL_ROLE_TOPLEVEL) {
        wl_resource_post_error(r, ZXDG_IMPORTED_V2_ERROR_INVALID_SURFACE, "child is not a toplevel");
        return;
    }
    pthread_mutex_lock(&foreign_lock);
    if (i->source && i->source->surface)
        awl_xdg_set_parent(s, i->source->surface, i);
    pthread_mutex_unlock(&foreign_lock);
}
static const struct zxdg_exported_v2_interface exported_impl = { .destroy = destroy_request };
static const struct zxdg_imported_v2_interface imported_impl = {
    .destroy = destroy_request, .set_parent_of = set_parent_of,
};

static void export_toplevel(struct wl_client* c, struct wl_resource* r, uint32_t id, struct wl_resource* surface) {
    struct awl_surface* s = wl_resource_get_user_data(surface);
    if (!s || s->role != AWL_ROLE_TOPLEVEL) {
        wl_resource_post_error(r, ZXDG_EXPORTER_V2_ERROR_INVALID_SURFACE, "surface is not a toplevel");
        return;
    }
    struct exported* e = calloc(1, sizeof(*e));
    if (!e) { wl_resource_post_no_memory(r); return; }
    unsigned char random[32];
    if (getrandom(random, sizeof(random), 0) != sizeof(random)) {
        free(e); wl_resource_post_no_memory(r); return;
    }
    for (size_t n = 0; n < sizeof(random); n++)
        snprintf(e->handle + n*2, 3, "%02x", random[n]);
    e->res = wl_resource_create(c, &zxdg_exported_v2_interface, 1, id);
    if (!e->res) { free(e); wl_resource_post_no_memory(r); return; }
    e->surface = s->id;
    wl_resource_set_implementation(e->res, &exported_impl, e, exported_destroy);
    pthread_mutex_lock(&foreign_lock);
    wl_list_insert(&exports, &e->link);
    pthread_mutex_unlock(&foreign_lock);
    zxdg_exported_v2_send_handle(e->res, e->handle);
}
static void import_toplevel(struct wl_client* c, struct wl_resource* r, uint32_t id, const char* handle) {
    struct imported* i = calloc(1, sizeof(*i));
    if (!i) { wl_resource_post_no_memory(r); return; }
    i->res = wl_resource_create(c, &zxdg_imported_v2_interface, 1, id);
    if (!i->res) { free(i); wl_resource_post_no_memory(r); return; }
    wl_resource_set_implementation(i->res, &imported_impl, i, imported_destroy);
    pthread_mutex_lock(&foreign_lock);
    struct exported* e;
    wl_list_for_each(e, &exports, link) {
        if (e->surface && strcmp(e->handle, handle) == 0) { i->source = e; break; }
    }
    wl_list_insert(&imports, &i->link);
    if (!i->source) zxdg_imported_v2_send_destroyed(i->res);
    pthread_mutex_unlock(&foreign_lock);
}
static const struct zxdg_exporter_v2_interface exporter_impl = {
    .destroy = destroy_request, .export_toplevel = export_toplevel,
};
static const struct zxdg_importer_v2_interface importer_impl = {
    .destroy = destroy_request, .import_toplevel = import_toplevel,
};
static void bind_exporter(struct wl_client* c, void* data, uint32_t version, uint32_t id) {
    struct wl_resource* r = wl_resource_create(c, &zxdg_exporter_v2_interface, 1, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &exporter_impl, NULL, NULL);
}
static void bind_importer(struct wl_client* c, void* data, uint32_t version, uint32_t id) {
    struct wl_resource* r = wl_resource_create(c, &zxdg_importer_v2_interface, 1, id);
    if (!r) { wl_client_post_no_memory(c); return; }
    wl_resource_set_implementation(r, &importer_impl, NULL, NULL);
}
void awl_foreign_surface_gone(struct awl_surface* s) {
    pthread_mutex_lock(&foreign_lock);
    struct exported* e;
    wl_list_for_each(e, &exports, link) if (e->surface == s->id) revoke_export(e);
    pthread_mutex_unlock(&foreign_lock);
}
void awl_foreign_setup(void) {
    wl_list_init(&exports); wl_list_init(&imports);
    wl_global_create(g_srv.display, &zxdg_exporter_v2_interface, 1, NULL, bind_exporter);
    wl_global_create(g_srv.display, &zxdg_importer_v2_interface, 1, NULL, bind_importer);
}
