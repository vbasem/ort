/*
 * Copyright (C) 2019-2021 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.model.utils

import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCase
import io.kotest.matchers.shouldBe

import java.io.File

import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.utils.safeMkdirs
import org.ossreviewtoolkit.utils.storage.LocalFileStorage
import org.ossreviewtoolkit.utils.test.createDefault
import org.ossreviewtoolkit.utils.test.createTestTempDir

private val PROVENANCE = RepositoryProvenance(
    vcsInfo = VcsInfo(
        type = VcsType.GIT,
        url = "url",
        revision = "0000000000000000000000000000000000000000",
        resolvedRevision = "0000000000000000000000000000000000000000"
    )
)

class FileArchiverTest : StringSpec() {
    private lateinit var workingDir: File
    private lateinit var storageDir: File
    private lateinit var targetDir: File
    private lateinit var storage: LocalFileStorage

    override fun beforeTest(testCase: TestCase) {
        workingDir = createTestTempDir("workingDir")
        storageDir = createTestTempDir("storageDir")
        targetDir = createTestTempDir("targetDir")
        storage = LocalFileStorage(storageDir)
    }

    private fun createFile(path: String) {
        val file = workingDir.resolve(path)
        file.parentFile.safeMkdirs()
        file.writeText(path)
    }

    private fun File.assertFileContent(path: String) {
        val file = resolve(path)
        file.isFile shouldBe true
        file.readText() shouldBe path
    }

    init {
        "All files matching any of the patterns are archived" {
            createFile("a")
            createFile("b")
            createFile("d/a")
            createFile("d/b")

            val archiver = FileArchiver(listOf("a", "**/a"), storage)

            archiver.archive(workingDir, PROVENANCE)
            archiver.unarchive(targetDir, PROVENANCE)

            targetDir.assertFileContent("a")
            targetDir.assertFileContent("d/a")

            fun assertFileNotSaved(path: String) {
                val file = storageDir.resolve("save/$path")
                file.exists() shouldBe false
            }

            assertFileNotSaved("b")
            assertFileNotSaved("d/b")
        }

        "All archived files are unarchived" {
            createFile("a")
            createFile("b")
            createFile("c/a")
            createFile("c/b")

            val archiver = FileArchiver(listOf("**"), storage)
            archiver.archive(workingDir, PROVENANCE)

            val result = archiver.unarchive(targetDir, PROVENANCE)

            result shouldBe true
            with(targetDir) {
                assertFileContent("a")
                assertFileContent("b")
                assertFileContent("c/a")
                assertFileContent("c/b")
            }
        }

        "LICENSE files are archived by default, independently of the directory" {
            createFile("LICENSE")
            createFile("path/LICENSE")

            val archiver = FileArchiver.createDefault()
            archiver.archive(workingDir, PROVENANCE)
            archiver.unarchive(targetDir, PROVENANCE)

            with(targetDir) {
                assertFileContent("LICENSE")
                assertFileContent("path/LICENSE")
            }
        }

        "The pattern matching is case-insensitive" {
            createFile("a/LICENSE")
            createFile("b/License")
            createFile("c/license")
            createFile("d/LiCeNsE")

            val archiver = FileArchiver.createDefault()
            archiver.archive(workingDir, PROVENANCE)
            archiver.unarchive(targetDir, PROVENANCE)

            with(targetDir) {
                assertFileContent("a/LICENSE")
                assertFileContent("b/License")
                assertFileContent("c/license")
                assertFileContent("d/LiCeNsE")
            }
        }
    }
}
