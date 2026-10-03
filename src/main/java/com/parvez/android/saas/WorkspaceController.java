package com.parvez.android.saas;

import com.parvez.android.dto.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Workspaces", description = "Workspace signup and current plan usage")
public class WorkspaceController {
    private final WorkspaceAccounts accounts;
    public WorkspaceController(WorkspaceAccounts accounts) { this.accounts = accounts; }
    @Operation(summary = "Create a workspace and owner account", description = "Public endpoint; authentication is not required. Creates an empty FREE workspace with a 100-book limit. Passwords are stored as salted PBKDF2 hashes. Log in at POST /api/auth/login to obtain access and refresh tokens.")
    @ApiResponse(responseCode = "201", description = "Workspace created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SignupResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid signup fields or JSON", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Email already registered; no workspace is created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signup(@Valid @RequestBody WorkspaceAccounts.Signup request) {
        return accounts.register(request);
    }
    @Operation(summary = "Get your workspace and usage", description = "Returns the authenticated owner's workspace, current entitlement and number of books. Plan changes are performed by an operator; this API does not process payments.")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "basicAuth")
    @ApiResponse(responseCode = "200", description = "Current workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = WorkspaceResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid owner credentials", content = @Content)
    @ApiResponse(responseCode = "403", description = "Super Admin has no workspace", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/workspace")
    public Map<String, Object> workspace() { return accounts.current(); }

    @Schema(name = "SignupResponse")
    public record SignupResponse(UUID workspaceId,
                                 @Schema(example="My Library") String workspaceName,
                                 @Schema(example="owner@example.com") String email,
                                 @Schema(allowableValues={"FREE"}, example="FREE") String plan) {}

    @Schema(name = "WorkspaceResponse")
    public record WorkspaceResponse(UUID id,
                                    @Schema(example="My Library") String name,
                                    @Schema(allowableValues={"FREE", "PRO"}, example="FREE") String plan,
                                    @Schema(description="Maximum books allowed", example="100") int book_limit,
                                    @Schema(description="Books currently stored", example="1") long books_used) {}

}

