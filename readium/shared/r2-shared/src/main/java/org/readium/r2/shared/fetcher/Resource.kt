/*
 * Module: r2-shared-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared.fetcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.extensions.read
import org.readium.r2.shared.parser.xml.ElementNode
import org.readium.r2.shared.parser.xml.XmlParser
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.util.Try
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset


typealias ResourceTry<SuccessT> = Try<SuccessT, Resource.Error>

/**
 * Implements the transformation of a Resource. It can be used, for example, to decrypt,
 * deobfuscate, inject CSS or JavaScript, correct content – e.g. adding a missing dir="rtl" in an
 * HTML document, pre-process – e.g. before indexing a publication's content, etc.
 *
 * If the transformation doesn't apply, simply return resource unchanged.
 */
typealias ResourceTransformer = (Resource) -> Resource

/**
 * Acts as a proxy to an actual resource by handling read access.
 */
interface Resource {

    /**
     * Returns the link from which the resource was retrieved.
     *
     * It might be modified by the [Resource] to include additional metadata, e.g. the
     * `Content-Type` HTTP header in [Link.type].
     */
    suspend fun link(): Link

    /**
     * Returns data length from metadata if available, or calculated from reading the bytes otherwise.
     *
     * This value must be treated as a hint, as it might not reflect the actual bytes length. To get
     * the real length, you need to read the whole resource.
     */
    suspend fun length(): ResourceTry<Long>

    /**
     * Reads the bytes at the given range.
     *
     * When [range] is null, the whole content is returned. Out-of-range indexes are clamped to the
     * available length automatically.
     */
    suspend fun read(range: LongRange? = null): ResourceTry<ByteArray>

    /**
     * Reads the full content as a [String].
     *
     * If [charset] is null, then it is parsed from the `charset` parameter of link().type,
     * or falls back on UTF-8.
     */
    suspend fun readAsString(charset: Charset? = null): ResourceTry<String> =
        read().mapCatching {
            String(it, charset = charset ?: link().mediaType?.charset ?: Charsets.UTF_8)
        }

    /**
     * Reads the full content as a JSON object.
     */
    suspend fun readAsJson(): ResourceTry<JSONObject> =
        readAsString(charset = Charsets.UTF_8).mapCatching { JSONObject(it) }

    /**
     * Reads the full content as an XML document.
     */
    suspend fun readAsXml(): ResourceTry<ElementNode> =
        read().mapCatching { XmlParser().parse(ByteArrayInputStream(it)) }

    /**
     * Closes any opened file handles.
     */
    suspend fun close()

    /**
     * Errors occurring while accessing a resource.
     */
    sealed class Error(cause: Throwable? = null) : Throwable(cause) {

        /** Equivalent to a 404 HTTP error. */
        object NotFound : Error()

        /**
         * Equivalent to a 403 HTTP error.
         *
         * This can be returned when trying to read a resource protected with a DRM that is not unlocked.
         */
        object Forbidden : Error()

        /**
         * Equivalent to a 503 HTTP error.
         *
         * Used when the source can't be reached, e.g. no Internet connection, or an issue with the
         * file system. Usually this is a temporary error.
         */
        object Unavailable : Error()

        /** For any other error, such as HTTP 500. */
        class Other(cause: Throwable) : Error(cause)
    }
}

/** Creates a Resource that will always return the given [error]. */
class FailureResource(private val link: Link, private val error: Resource.Error) : Resource {

    internal constructor(link: Link, cause: Throwable) : this(link, Resource.Error.Other(cause))

    override suspend fun link(): Link = link

    override suspend fun read(range: LongRange?): ResourceTry<ByteArray> = Try.failure(error)

    override suspend fun length():  ResourceTry<Long> = Try.failure(error)

    override suspend fun close() {}
}

/** Creates a Resource serving [ByteArray] computed from a factory that can fail. */
open class BytesResource(private val factory: suspend () -> Pair<Link, ResourceTry<ByteArray>>) : Resource {

    private lateinit var byteArray: ResourceTry<ByteArray>
    private lateinit var computedLink: Link

    private suspend fun maybeInitData() {
        if(!::byteArray.isInitialized || !::computedLink.isInitialized) {
            val res = factory()
            computedLink = res.first
            byteArray = res.second
        }
    }

    private suspend fun bytes(): ResourceTry<ByteArray> {
        maybeInitData()
        return byteArray
    }

    override suspend fun link(): Link {
        maybeInitData()
        return computedLink
    }

    override suspend fun read(range: LongRange?): ResourceTry<ByteArray> {
        if (range == null)
            return bytes()

        @Suppress("NAME_SHADOWING")
        val range = checkedRange(range)
        return bytes().map { it.sliceArray(range.map(Long::toInt)) }
    }

    override suspend fun length(): ResourceTry<Long> = byteArray.map { it.size.toLong() }

    override suspend fun close() {}
}

/** Creates a Resource serving a [String] computed from a factory that can fail. */
class StringResource(factory: suspend () -> Pair<Link, ResourceTry<String>>) : BytesResource(
    {
        val (link,res) = factory()
        Pair(link, res.mapCatching { it.toByteArray() })
    }
)

/**
 * A base class for a [Resource] which acts as a proxy to another one.
 *
 * Every function is delegating to the proxied resource, and subclasses should override some of them.
 */
abstract class ProxyResource(protected val resource: Resource) : Resource {

    override suspend fun link(): Link = resource.link()

    override suspend fun length(): ResourceTry<Long> = resource.length()

    override suspend fun read(range: LongRange?): ResourceTry<ByteArray> = resource.read(range)

    override suspend fun close() = resource.close()
}

class RoutingResource(private val resourceFactory: suspend () -> Resource) : Resource {

    private lateinit var _resource: Resource

    private suspend fun resource(): Resource {
        if (!::_resource.isInitialized)
            _resource = resourceFactory()

        return _resource
    }

    override suspend fun link(): Link = resource().link()

    override suspend fun length(): ResourceTry<Long> = resource().length()

    override suspend fun read(range: LongRange?): ResourceTry<ByteArray> = resource().read(range)

    override suspend fun close() = resource().close()
}

internal abstract class StreamResource : Resource {

    abstract fun stream(): ResourceTry<InputStream>

    /** An estimate of data length from metadata */
    protected abstract val metadataLength: Long?

    override suspend fun read(range: LongRange?): ResourceTry<ByteArray> =
        if (range == null)
            readFully()
        else
            readRange(range)

    private suspend fun readFully(): ResourceTry<ByteArray> =
        stream().mapCatching { stream ->
            stream.use {
                withContext(Dispatchers.IO) {
                    it.readBytes()
                }
            }
        }

    private suspend fun readRange(range: LongRange): ResourceTry<ByteArray> =
        stream().mapCatching { stream ->
            @Suppress("NAME_SHADOWING")
            val range = checkedRange(range)

            withContext(Dispatchers.IO) {
                stream.use {
                    val skipped = it.skip(range.first)
                    val length = range.last - range.first + 1
                    val bytes = it.read(length)
                    if (skipped != range.first && bytes.isNotEmpty()) {
                        throw Exception("Unable to skip enough bytes")
                    }
                    return@use bytes
                }
            }
        }

    override suspend fun length(): ResourceTry<Long> =
        metadataLength?.let { Try.success(it) }
            ?: readFully().map { it.size.toLong() }
}

/**
 * Maps the result with the given [transform]
 *
 * If the [transform] throws an [Exception], it is wrapped in a failure with Resource.Error.Other.
 */
suspend fun <R, S> ResourceTry<S>.mapCatching(transform: suspend (value: S) -> R): ResourceTry<R> =
    try {
        Try.success((transform(getOrThrow())))
    } catch (e: Resource.Error) {
        Try.failure(e)
    } catch (e: Exception) {
        Try.failure(Resource.Error.Other(e))
    }

suspend fun <R, S> ResourceTry<S>.flatMapCatching(transform: suspend (value: S) -> ResourceTry<R>): ResourceTry<R> =
    mapCatching(transform).flatMap { it }

private fun checkedRange(range: LongRange): LongRange = when {
    range.first >= range.last -> 0 until 0L
    range.last - range.first + 1 > Int.MAX_VALUE -> throw IllegalArgumentException("Range length greater than Int.MAX_VALUE")
    else -> LongRange(range.first.coerceAtLeast(0), range.last)
}
