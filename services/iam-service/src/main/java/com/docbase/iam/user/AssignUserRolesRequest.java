package com.docbase.iam.user;
import jakarta.validation.constraints.*;
import java.util.List;
public record AssignUserRolesRequest(@NotNull @Size(max=20) List<@NotNull @Positive Long> roleIds) {}
