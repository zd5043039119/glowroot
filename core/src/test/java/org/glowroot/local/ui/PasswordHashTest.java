/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.local.ui;

import java.security.GeneralSecurityException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PasswordHashTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void shouldThrowOnInvalidHash() throws Exception {
        thrown.expect(GeneralSecurityException.class);
        PasswordHash.validatePassword("abc",
                "b2aed396b2b8d74002ad1f138bd4de55:e6a3bd63b314e238a27641c821716f52");
    }

    @Test
    public void shouldThrowOnNonHexSalt() throws Exception {
        thrown.expect(GeneralSecurityException.class);
        PasswordHash.validatePassword("abc",
                "b2aed396b2b8d74002ad1f138bd4de55:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz:100000");
    }

    @Test
    public void shouldThrowOnIterationCountNotANumber() throws Exception {
        thrown.expect(GeneralSecurityException.class);
        PasswordHash.validatePassword("abc",
                "b2aed396b2b8d74002ad1f138bd4de55:e6a3bd63b314e238a27641c821716f52:abc");
    }
}
