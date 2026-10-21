package com.ticketresale.user;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 150) String fullName) {
    }

    public record UserResponse(Long id, String email, String fullName, Instant createdAt) {

        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getCreatedAt());
        }
    }
}
