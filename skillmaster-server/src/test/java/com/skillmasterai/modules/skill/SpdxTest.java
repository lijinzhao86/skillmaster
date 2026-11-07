package com.skillmasterai.modules.skill;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The fixed table both the v49 reader and the v50 sync rely on. */
class SpdxTest {

    @Test
    void aThreeClauseBsdIsRecognised() {
        assertThat(Spdx.detect("""
                Redistribution and use in source and binary forms, with or without modification,
                are permitted provided that the following conditions are met.
                Neither the name of the copyright holder nor the names of its contributors
                may be used to endorse or promote products derived from this software.
                """)).isEqualTo("BSD-3-Clause");
    }

    @Test
    void aFourClauseBsdIsNotMistakenForTheThreeClauseOne() {
        assertThat(Spdx.detect("""
                Redistribution and use in source and binary forms, with or without modification,
                are permitted provided that the following conditions are met.
                All advertising materials mentioning features or use of this software must
                display the following acknowledgement.
                Neither the name of the copyright holder nor the names of its contributors
                may be used to endorse or promote products derived from this software.
                """)).isEqualTo("NOASSERTION");
    }
}
