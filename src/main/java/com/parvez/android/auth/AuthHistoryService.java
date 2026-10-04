package com.parvez.android.auth;

import com.parvez.android.saas.WorkspacePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Authorization precedes cursor lookup; repeatable reads keep lookup and page consistent. */
@Service
public class AuthHistoryService {
    private final JdbcTemplate jdbc;

    public AuthHistoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record LoginEntry(UUID id, String email, UUID workspaceId, String workspaceName, Instant loggedInAt,
                             String ipAddress, String browser, String device, String userAgent) {}
    public record PasswordChangeEntry(UUID id, String email, UUID workspaceId, String workspaceName,
                                      PasswordChangeNotifications.Source source, Instant changedAt,
                                      String ipAddress, String browser, String device, String userAgent) {}
    public record LoginPage(List<LoginEntry> items, UUID nextCursor) {}
    public record PasswordChangePage(List<PasswordChangeEntry> items, UUID nextCursor) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LoginPage ownLogins(int limit, UUID cursor) {
        return logins(WorkspacePrincipal.currentWorkspace(), limit, cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PasswordChangePage ownPasswordChanges(int limit, UUID cursor) {
        return passwordChanges(WorkspacePrincipal.currentWorkspace(), limit, cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LoginPage allLogins(UUID workspaceId, int limit, UUID cursor) {
        WorkspacePrincipal.requireSuperAdmin();
        return logins(workspaceId, limit, cursor);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PasswordChangePage allPasswordChanges(UUID workspaceId, int limit, UUID cursor) {
        WorkspacePrincipal.requireSuperAdmin();
        return passwordChanges(workspaceId, limit, cursor);
    }

    private LoginPage logins(UUID workspace, int limit, UUID cursor) {
        var page = page(History.LOGIN, workspace, limit, cursor, (rs, row) -> new LoginEntry(
                rs.getObject("id", UUID.class), rs.getString("email"), rs.getObject("workspace_id", UUID.class),
                rs.getString("workspace_name"), rs.getTimestamp("logged_in_at").toInstant(),
                rs.getString("ip_address"), rs.getString("browser"), rs.getString("device"), rs.getString("user_agent")),
                LoginEntry::id);
        return new LoginPage(page.items(), page.nextCursor());
    }

    private PasswordChangePage passwordChanges(UUID workspace, int limit, UUID cursor) {
        var page = page(History.PASSWORD_CHANGE, workspace, limit, cursor, (rs, row) -> new PasswordChangeEntry(
                rs.getObject("id", UUID.class), rs.getString("email"), rs.getObject("workspace_id", UUID.class),
                rs.getString("workspace_name"), PasswordChangeNotifications.Source.valueOf(rs.getString("source")),
                rs.getTimestamp("changed_at").toInstant(), rs.getString("ip_address"), rs.getString("browser"),
                rs.getString("device"), rs.getString("user_agent")), PasswordChangeEntry::id);
        return new PasswordChangePage(page.items(), page.nextCursor());
    }

    private <T> Page<T> page(History history, UUID workspace, int limit, UUID cursor, RowMapper<T> mapper, Function<T, UUID> id) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History limit must be between 1 and 100");
        // Table/column names come exclusively from this closed enum; user values are bound parameters.
        String scope = workspace == null ? "" : " AND h.workspace_id=?";
        var parameters = new ArrayList<Object>();
        if (workspace != null) parameters.add(workspace);
        String older = "";
        if (cursor != null) {
            var anchorParameters = new ArrayList<Object>();
            anchorParameters.add(cursor);
            if (workspace != null) anchorParameters.add(workspace);
            var anchors = jdbc.query("SELECT h." + history.time + " FROM " + history.table + " h WHERE h.id=?" + scope,
                    (rs, row) -> rs.getTimestamp(1), anchorParameters.toArray());
            if (anchors.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "History cursor is unavailable for this scope");
            older = " AND (h." + history.time + ", h.id) < (?, ?)";
            parameters.add(anchors.getFirst());
            parameters.add(cursor);
        }
        parameters.add(limit + 1);
        var rows = jdbc.query("SELECT h.*, w.name AS workspace_name FROM " + history.table
                + " h LEFT JOIN workspaces w ON w.id=h.workspace_id WHERE true" + scope + older
                + " ORDER BY h." + history.time + " DESC, h.id DESC LIMIT ?", mapper, parameters.toArray());
        boolean hasMore = rows.size() > limit;
        var items = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        return new Page<>(items, hasMore ? id.apply(items.getLast()) : null);
    }

    private enum History {
        LOGIN("login_history", "logged_in_at"), PASSWORD_CHANGE("password_change_history", "changed_at");
        final String table;
        final String time;
        History(String table, String time) { this.table = table; this.time = time; }
    }

    private record Page<T>(List<T> items, UUID nextCursor) {}
}
