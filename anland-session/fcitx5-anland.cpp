/* Android/PC IME text -> the focused Fcitx 5 input context. This uses the
 * normal frontend commit/preedit APIs, without keysym tricks or clipboard
 * ownership changes. Socket requests run on Fcitx's event loop. */
#include "desktop_ime_wire.h"
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/instance.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputpanel.h>
#include <fcitx-utils/event.h>
#include <fcitx-utils/utf8.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/random.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <vector>

using namespace fcitx;

class AnlandDesktopIme : public AddonInstance {
    Instance *instance_;
    int listener_ = -1;
    std::string path_;
    uint64_t generation_ = 1;
    std::unique_ptr<EventSourceIO> listenerEvent_;
    std::map<int, std::unique_ptr<EventSourceIO>> peers_;
    std::vector<std::unique_ptr<HandlerTableEntry<EventHandler>>> watches_;

    static size_t byteOffset(const std::string &text, unsigned int chars) {
        size_t i = 0;
        while (i < text.size() && chars) {
            ++i;
            while (i < text.size() && (static_cast<unsigned char>(text[i]) & 0xc0) == 0x80) ++i;
            --chars;
        }
        return i;
    }
    static unsigned int count(const std::string &text) {
        unsigned int n = 0;
        for (unsigned char c : text) if ((c & 0xc0) != 0x80) ++n;
        return n;
    }
    bool request(EventSourceIO *source, int fd) {
        char packet[sizeof(anland_ime_request) + ANLAND_IME_TEXT_MAX];
        ssize_t size = recv(fd, packet, sizeof(packet), MSG_TRUNC);
        anland_ime_reply reply{};
        reply.magic = ANLAND_IME_MAGIC;
        reply.status = -1;
        std::string surrounding;
        if (size >= static_cast<ssize_t>(sizeof(anland_ime_request)) && size <= static_cast<ssize_t>(sizeof(packet))) {
            anland_ime_request req;
            memcpy(&req, packet, sizeof(req));
            std::string text(packet + sizeof(req), size - sizeof(req));
            InputContext *ic = instance_->lastFocusedInputContext();
            bool valid = req.magic == ANLAND_IME_MAGIC && req.op <= 6 && req.length == text.size()
                && req.a >= 0 && req.b >= 0 && utf8::validate(text);
            if (valid && (!req.op || (ic && ic->hasFocus() && req.context == generation_))) {
                if (!ic || !ic->hasFocus()) {
                    reply.status = 2;
                } else {
                    bool applied = true;
                    if (req.op == 3 || req.op == 5 || req.op == 6) {
                        const auto &sur = ic->surroundingText();
                        if (!sur.isValid() || !ic->capabilityFlags().test(CapabilityFlag::SurroundingText)) {
                            applied = false;
                        } else {
                            size_t cursor = byteOffset(sur.text(), sur.cursor());
                            if (static_cast<size_t>(req.a) > cursor || cursor + req.b > sur.text().size()) {
                                applied = false;
                            } else {
                                auto before = sur.text().substr(cursor - req.a, req.a);
                                auto after = sur.text().substr(cursor, req.b);
                                if (!utf8::validate(before) || !utf8::validate(after)) applied = false;
                                else ic->deleteSurroundingText(-static_cast<int>(count(before)), count(before) + count(after));
                            }
                        }
                    }
                    if (req.op == 4) applied = false; // Fcitx cannot set a remote arbitrary selection.
                    if (applied && (req.op == 1 || req.op == 5)) {
                        ic->inputPanel().setClientPreedit(Text());
                        ic->updatePreedit();
                        ic->commitString(text);
                    } else if (applied && (req.op == 2 || req.op == 6)) {
                        if (!ic->capabilityFlags().test(CapabilityFlag::Preedit)) {
                            // Keep composition in the Android virtual editor;
                            // commit still works for frontends without preedit.
                        } else {
                            Text preedit(text);
                            preedit.setCursor(count(text.substr(0, std::min<size_t>(req.a, text.size()))));
                            ic->inputPanel().setClientPreedit(preedit);
                            ic->updatePreedit();
                        }
                    }
                    reply.status = applied ? 1 : -1;
                    reply.context = generation_;
                    auto rect = ic->cursorRect();
                    reply.x = rect.left(); reply.y = rect.top();
                    reply.width = rect.width(); reply.height = rect.height();
                    const auto caps = ic->capabilityFlags();
                    if (caps.test(CapabilityFlag::Password) || caps.test(CapabilityFlag::Sensitive)) {
                        reply.flags |= ANLAND_IME_PASSWORD;
                    } else if (ic->surroundingText().isValid() && caps.test(CapabilityFlag::SurroundingText)) {
                        const auto &sur = ic->surroundingText();
                        size_t cursor = byteOffset(sur.text(), sur.cursor());
                        size_t anchor = byteOffset(sur.text(), sur.anchor());
                        // Keep a bounded, codepoint-aligned context around both
                        // selection ends. Omit context if the selection is huge.
                        size_t begin = std::min(cursor, anchor) > 1500 ? std::min(cursor, anchor) - 1500 : 0;
                        while (begin && (static_cast<unsigned char>(sur.text()[begin]) & 0xc0) == 0x80) --begin;
                        size_t end = std::min(sur.text().size(), begin + ANLAND_IME_TEXT_MAX);
                        while (end < sur.text().size() && (static_cast<unsigned char>(sur.text()[end]) & 0xc0) == 0x80) --end;
                        if (std::max(cursor, anchor) <= end) {
                            surrounding = sur.text().substr(begin, end - begin);
                            reply.flags |= ANLAND_IME_SURROUNDING;
                            reply.cursor = cursor - begin; reply.anchor = anchor - begin;
                        }
                    }
                }
            }
        }
        reply.length = surrounding.size();
        char output[sizeof(reply) + ANLAND_IME_TEXT_MAX];
        memcpy(output, &reply, sizeof(reply));
        memcpy(output + sizeof(reply), surrounding.data(), surrounding.size());
        send(fd, output, sizeof(reply) + surrounding.size(), MSG_NOSIGNAL);
        // Do not destroy the event source while its callback is executing.
        // Disabled entries are reaped before the next accept.
        source->setEnabled(false);
        close(fd);
        return true;
    }
public:
    explicit AnlandDesktopIme(Instance *instance) : instance_(instance) {
        const char *runtime = getenv("ANLAND_RUNTIME_DIR");
        const char *type = getenv("XDG_SESSION_TYPE");
        const char *desktop = getenv("XDG_CURRENT_DESKTOP");
        if (!runtime || !type || strcmp(type, "x11") || !desktop || strcmp(desktop, "XFCE")) return;
        // A daemon restart must not reuse an old editor token.
        if (getrandom(&generation_, sizeof(generation_), 0) != sizeof(generation_)) return;
        generation_ &= INT64_MAX;
        if (!generation_) generation_ = 1;
        struct stat ns{};
        if (stat("/proc/self/ns/pid", &ns)) return;
        path_ = std::string(runtime) + "/sessions/p" + std::to_string(ns.st_ino)
            + "-u" + std::to_string(getuid()) + "/desktop-ime.sock";
        sockaddr_un address{};
        if (path_.size() >= sizeof(address.sun_path)) return;
        address.sun_family = AF_UNIX;
        memcpy(address.sun_path, path_.c_str(), path_.size() + 1);
        listener_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (listener_ < 0) return;
        // Fcitx --replace has stopped the old instance before addon loading.
        unlink(path_.c_str());
        if (bind(listener_, reinterpret_cast<sockaddr *>(&address), sizeof(address)) || listen(listener_, 8)) {
            close(listener_); listener_ = -1; return;
        }
        chmod(path_.c_str(), 0600);
        for (auto type : {EventType::InputContextFocusIn, EventType::InputContextFocusOut, EventType::InputContextDestroyed}) {
            watches_.push_back(instance_->watchEvent(type, EventWatcherPhase::Default,
                [this](Event &) { ++generation_; }));
        }
        listenerEvent_ = instance_->eventLoop().addIOEvent(listener_, IOEventFlag::In,
            [this](EventSourceIO *, int, IOEventFlags) {
                for (auto it = peers_.begin(); it != peers_.end();) {
                    if (!it->second->isEnabled()) it = peers_.erase(it);
                    else ++it;
                }
                int fd = accept4(listener_, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
                if (fd < 0) return true;
                ucred peer{};
                socklen_t len = sizeof(peer);
                // Only the privileged Android host daemon can submit text.
                if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &len) || peer.uid != 0 || peers_.size() >= 16) {
                    close(fd); return true;
                }
                peers_[fd] = instance_->eventLoop().addIOEvent(fd, IOEventFlag::In,
                    [this](EventSourceIO *source, int peerFd, IOEventFlags) { return request(source, peerFd); });
                return true;
            });
    }
    ~AnlandDesktopIme() override {
        listenerEvent_.reset();
        for (auto &peer : peers_) if (peer.second->isEnabled()) close(peer.first);
        peers_.clear();
        if (listener_ >= 0) { close(listener_); unlink(path_.c_str()); }
    }
};
class AnlandFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AnlandDesktopIme(manager->instance());
    }
};
FCITX_ADDON_FACTORY(AnlandFactory)
