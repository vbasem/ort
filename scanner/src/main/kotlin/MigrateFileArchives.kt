/*
 * Copyright (C) 2020 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.ossreviewtoolkit

import java.lang.StringBuilder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.config.FileArchiverConfiguration
import org.ossreviewtoolkit.model.config.FileStorageConfiguration
import org.ossreviewtoolkit.model.config.HttpFileStorageConfiguration
import org.ossreviewtoolkit.model.config.PostgresStorageConfiguration
import org.ossreviewtoolkit.model.config.createFileArchiver
import org.ossreviewtoolkit.model.utils.DatabaseUtils
import org.ossreviewtoolkit.model.utils.FileArchiverFileStorage
import org.ossreviewtoolkit.model.utils.FileArchiverStorage
import org.ossreviewtoolkit.model.utils.PostgresFileArchiverStorage
import org.ossreviewtoolkit.scanner.ScanResultsStorage
import org.ossreviewtoolkit.scanner.storages.PostgresStorage
import org.ossreviewtoolkit.spdx.toHexString

/**
 * BEGIN CONFIG
 */
// http file storage for the file archiver:
private const val JFROG_API_KEY = ""
private const val ARCHIVES_URL = ""

private const val PG_HOST = ""
private const val PG_PORT = ""
private const val PG_SCHEMA = ""
private const val PG_DATABASE = ""
private const val PG_USER = ""
private const val PG_PASSWORD = ""
/**
 * END CONFIG
 */
private val POSTGRES_STORAGE_CONFIG = PostgresStorageConfiguration(
    url = "jdbc:postgresql://$PG_HOST:$PG_PORT/$PG_DATABASE",
    schema=PG_SCHEMA,
    username=PG_USER,
    password=PG_PASSWORD,
    sslmode = "disable"
)

private fun createPostgresStorage(): PostgresStorage {
    return ScanResultsStorage.createPostgresStorage(POSTGRES_STORAGE_CONFIG)
}

private fun createSourceFileArchiverStorage(): FileArchiverStorage =
    FileArchiverFileStorage(
        FileStorageConfiguration(
            httpFileStorage = HttpFileStorageConfiguration(
                url = ARCHIVES_URL,
                headers = mapOf("X-JFrog-Art-Api" to JFROG_API_KEY)
            )
        ).createFileStorage()
    )

private fun createTargetFileArchiverStorage(): PostgresFileArchiverStorage {
    FileArchiverConfiguration(
        postgresStorage = POSTGRES_STORAGE_CONFIG
    ).createFileArchiver()

    val dataSource = DatabaseUtils.createHikariDataSource(
        config = POSTGRES_STORAGE_CONFIG,
        applicationNameSuffix = "file-archiver"
    )

    return PostgresFileArchiverStorage(dataSource)
}

private val SHA1_DIGEST by lazy { MessageDigest.getInstance("SHA-1") }

/**
 * Calculate the SHA-1 hash of the storage key of this [Provenance] instance.
 */
private fun Provenance.hash(): String {
    val key = vcsInfo?.let {
        "${it.type}${it.url}${it.resolvedRevision}"
    } ?: sourceArtifact!!.let {
        "${it.url}${it.hash.value}"
    }

    return SHA1_DIGEST.digest(key.toByteArray()).toHexString()
}

fun main() {
    val scanResultStorage = createPostgresStorage()
    val sourceStorage = createSourceFileArchiverStorage()
    val targetStorage = createTargetFileArchiverStorage()

    println("Fetching all id / provenance pairs from the scan result storage.")
    val queue = scanResultStorage.getEntries().mapTo(ConcurrentLinkedQueue()) { it }
    println("Fetched ${queue.size} entries.")

    val numThreads = 64
    val executor = Executors.newFixedThreadPool(numThreads)
    val doneEntries = AtomicInteger()
    val totalEntries = queue.size
    val failureCount = AtomicInteger()

    for (i in 1 .. 64) {
        fun migrate(provenance: Provenance) {
            val sb = StringBuilder()

            val entryIndex = doneEntries.addAndGet(1)
            sb.appendLine("[$entryIndex/$totalEntries] Creating entries from '${provenance.hash()}'.")

            val file = sourceStorage.getArchive(provenance)
            if (file != null) {
                targetStorage.addArchive(provenance, file)
                sb.appendLine("migrated ${provenance.hash()}")
                file.delete()
            } else {
                failureCount.getAndIncrement()
                sb.appendLine("skipped ${provenance.hash()}")
            }

            println("[thread-$i]:\n${sb}" )
        }

        executor.execute {
            var provenance = queue.poll()
            while (provenance != null) {
                migrate(provenance)
                provenance = queue.poll()
            }
        }
    }
    println("Awaiting termination...")
    executor.shutdown()
    executor.awaitTermination(21, TimeUnit.DAYS)
    println("#total-failures: $failureCount")
}
