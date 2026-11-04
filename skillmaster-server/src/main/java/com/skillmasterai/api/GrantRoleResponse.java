package com.skillmasterai.api;

import com.skillmasterai.usecase.ChangeSkillGrantRoleUseCase;

/** The answer to a role change: who, the rung they now hold, and whether it moved. */
record GrantRoleResponse(String handle, String role, boolean changed) {

    static GrantRoleResponse of(ChangeSkillGrantRoleUseCase.Result result) {
        return new GrantRoleResponse(result.handle(), result.role(), result.changed());
    }
}
