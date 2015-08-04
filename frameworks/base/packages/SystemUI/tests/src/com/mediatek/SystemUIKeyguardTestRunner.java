/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mediatek.systemui;

import com.mediatek.systemui.keyguard.UnlockTest ;

import junit.framework.TestSuite;

/**
 * Testrunner for Keyguard.
 */
public class SystemUIKeyguardTestRunner extends JUnitInstrumentationTestRunner {
    /**
     * Add keyguard test suite.
     * @return Test suite
     */
    @Override
    public TestSuite getAllTests() {
        TestSuite tests = new TestSuite();
        tests.addTestSuite(UnlockTest.class);
        return tests;
    }
}


