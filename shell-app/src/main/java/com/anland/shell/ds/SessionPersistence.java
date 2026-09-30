package com.anland.shell.ds;

/** Keep detached Linux applications alive after the launch login shell exits. */
public final class SessionPersistence {
    private SessionPersistence() {}

    public static String prepare(String user, String uid) {
        if (user == null || user.isEmpty() || uid == null || !uid.matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("A non-root session user is required");
        return "set -e\n"
                + "user=" + ShellUtils.shQuote(user) + "\n"
                + "uid=" + ShellUtils.shQuote(uid) + "\n"
                + "[ \"$(id -u -- \"$user\")\" = \"$uid\" ] || exit 1\n"
                + "if [ \"$(loginctl show-user \"$user\" -p Linger --value 2>/dev/null)\" != yes ]; then\n"
                + "  loginctl enable-linger \"$user\"\n"
                + "fi\n"
                + "[ \"$(loginctl show-user \"$user\" -p Linger --value)\" = yes ] || {\n"
                + "  echo 'Cannot retain the Linux user session after logout' >&2\n"
                + "  exit 1\n"
                + "}\n"
                + "systemctl start \"user@$uid.service\"\n";
    }
}
