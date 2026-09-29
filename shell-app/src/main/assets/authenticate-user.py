"""Authenticate with the container's PAM policy. Secrets arrive only on stdin.

Do not use `su`'s PAM service: pam_rootok would bypass password validation because
Droidspaces enters as root. The normal `other` policy checks authentication AND
account status. No shadow parsing, password changes, session files or shell echo.
"""
import ctypes as c
import json
import sys


def authenticate(username, password):
    pam = c.CDLL("libpam.so.0")
    libc = c.CDLL(None)

    class Message(c.Structure):
        _fields_ = [("style", c.c_int), ("text", c.c_char_p)]

    class Response(c.Structure):
        _fields_ = [("text", c.c_void_p), ("code", c.c_int)]

    callback = c.CFUNCTYPE(c.c_int, c.c_int, c.POINTER(c.POINTER(Message)),
                          c.POINTER(c.POINTER(Response)), c.c_void_p)

    class Conversation(c.Structure):
        _fields_ = [("callback", callback), ("data", c.c_void_p)]

    libc.calloc.argtypes = [c.c_size_t, c.c_size_t]
    libc.calloc.restype = c.c_void_p
    libc.strdup.argtypes = [c.c_char_p]
    libc.strdup.restype = c.c_void_p
    libc.free.argtypes = [c.c_void_p]

    @callback
    def converse(count, messages, output, _):
        if not 0 < count <= 32:
            return 19  # PAM_CONV_ERR
        pointer = libc.calloc(count, c.sizeof(Response))
        if not pointer:
            return 5
        responses = c.cast(pointer, c.POINTER(Response))
        try:
            for i in range(count):
                style = messages[i].contents.style
                if style in (1, 2):  # ECHO_OFF=password, ECHO_ON=user
                    value = password if style == 1 else username
                    responses[i].text = libc.strdup(value.encode("utf-8"))
                    if not responses[i].text:
                        raise MemoryError()
                elif style not in (3, 4):  # Never echo PAM info/error messages.
                    raise ValueError()
            output[0] = responses  # PAM owns and frees these allocations.
            return 0
        except Exception:
            for i in range(count):
                libc.free(responses[i].text)
            libc.free(pointer)
            return 19

    handle = c.c_void_p()
    conversation = Conversation(converse, None)
    pam.pam_start.argtypes = [c.c_char_p, c.c_char_p, c.POINTER(Conversation), c.POINTER(c.c_void_p)]
    pam.pam_authenticate.argtypes = [c.c_void_p, c.c_int]
    pam.pam_acct_mgmt.argtypes = [c.c_void_p, c.c_int]
    pam.pam_end.argtypes = [c.c_void_p, c.c_int]
    status = pam.pam_start(b"other", username.encode("utf-8"), c.byref(conversation), c.byref(handle))
    if status == 0:
        try:
            status = pam.pam_authenticate(handle, 0x8000 | 1)  # SILENT | DISALLOW_NULL_AUTHTOK
            if status == 0:
                status = pam.pam_acct_mgmt(handle, 0x8000)
        finally:
            pam.pam_end(handle, status)
    return status


if __name__ == "__main__":
    try:
        request = json.loads(sys.stdin.buffer.read(65536))
        username, password = request["username"], request["password"]
        if not username or not password or "\0" in username or "\0" in password:
            raise ValueError()
        result = authenticate(username, password)
        print(json.dumps({"ok": result == 0, "code": result}))
        sys.exit(0 if result == 0 else 1)
    except Exception:
        print('{"ok":false,"code":-1}')
        sys.exit(2)
