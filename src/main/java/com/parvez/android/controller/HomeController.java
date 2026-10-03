package com.parvez.android.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Legacy", description = "Retained compatibility mappings; not application APIs")
@RestController
@RequestMapping("/")
public class HomeController {

    @Operation(summary = "Legacy root greeting (disabled)", description = "Retained mapping denied by the security policy. Use GET /actuator/health for liveness; this route is not an application API.")
    @ApiResponse(responseCode = "401", description = "Anonymous requests are rejected", content = @Content)
    @ApiResponse(responseCode = "403", description = "Authenticated requests are denied by security policy", content = @Content)
    @GetMapping
    public String sayHello() {
        return "Hello World!";
    }
}
