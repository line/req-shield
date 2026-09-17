/*
 *  Copyright 2024 LY Corporation
 *
 *  LY Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package config

import com.linecorp.cse.reqshield.spring.config.LibAutoConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the two auto-configuration registration files shipped in `src/main/resources` against a
 * silent typo: nothing else in the build loads them, so a broken class name would only surface at
 * runtime in a consuming application.
 */
class AutoConfigurationResourcesTest {
    private val expectedClassName = LibAutoConfiguration::class.java.name

    @Test
    fun `spring factories names the module's LibAutoConfiguration`() {
        val names = readClasspathResourceLines("META-INF/spring.factories")

        assertTrue(
            names.any { it.contains(expectedClassName) },
            "spring.factories should reference $expectedClassName, but was: $names",
        )
        assertClassLoadable(expectedClassName)
    }

    @Test
    fun `Boot 3 AutoConfiguration imports names the module's LibAutoConfiguration`() {
        val names = readClasspathResourceLines("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")

        assertTrue(
            names.any { it == expectedClassName },
            "AutoConfiguration.imports should list $expectedClassName, but was: $names",
        )
        assertClassLoadable(expectedClassName)
    }

    private fun readClasspathResourceLines(resourcePath: String): List<String> {
        val resource =
            checkNotNull(javaClass.classLoader.getResource(resourcePath)) {
                "Resource not found on classpath: $resourcePath"
            }
        return resource.openStream().bufferedReader().readLines().map { it.trim() }
    }

    private fun assertClassLoadable(className: String) {
        Class.forName(className)
    }
}
