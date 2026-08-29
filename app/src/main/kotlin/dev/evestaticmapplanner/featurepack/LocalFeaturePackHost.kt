package dev.evestaticmapplanner.featurepack

import dev.evestaticmapplanner.feature.api.FeaturePackContext
import dev.evestaticmapplanner.feature.api.FeaturePackDescriptor
import dev.evestaticmapplanner.feature.api.FeaturePackEntrypoint
import dev.evestaticmapplanner.feature.api.FeaturePackSession
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** A JAR found in the local external `feature-packs/<pack>/pack.jar` layout. */
data class LocalFeaturePackCandidate(
    val packDirectory: Path,
    val jar: Path,
)

enum class FeaturePackFailureKind {
    INVALID_DISCOVERY_PATH,
    INVALID_PACK_DIRECTORY,
    DISCOVERY_FAILED,
    MISSING_JAR,
    INVALID_JAR,
    INVALID_DESCRIPTOR,
    INCOMPATIBLE_FEATURE_API,
    CLASSLOADER_CREATION_FAILED,
    ZERO_ENTRYPOINTS,
    MULTIPLE_ENTRYPOINTS,
    SERVICE_LOADING_FAILED,
    DESCRIPTOR_FAILED,
    DESCRIPTOR_MISMATCH,
    CONTEXT_CREATION_FAILED,
    STARTUP_FAILED,
    CLOSE_FAILED,
}

data class FeaturePackFailure(
    val kind: FeaturePackFailureKind,
    val message: String,
    val cause: Throwable? = null,
)

data class FeaturePackDiscoveryReport(
    val candidates: List<LocalFeaturePackCandidate>,
    val failures: List<FeaturePackFailure>,
)

sealed interface FeaturePackLoadResult {
    data class Loaded(val pack: LoadedFeaturePack) : FeaturePackLoadResult

    data class Failed(val failure: FeaturePackFailure) : FeaturePackLoadResult
}

sealed interface FeaturePackCloseResult {
    data object Closed : FeaturePackCloseResult

    data class Failed(val failure: FeaturePackFailure) : FeaturePackCloseResult
}

fun interface FeaturePackContextFactory {
    fun create(descriptor: FeaturePackDescriptor): FeaturePackContext
}

/** Application-private cleanup for capabilities owned by a Pack context. */
internal fun interface FeaturePackContextLifecycle {
    fun closeHostResources()
}

/**
 * Local external Feature Pack host.
 *
 * Application-owned coordinators choose the discovery root and explicitly load
 * candidates; the host itself has no UI, network, database, or startup policy.
 */
class LocalFeaturePackHost private constructor(
    private val applicationClassLoader: ClassLoader,
    private val packClassLoaderFactory: (URL, ClassLoader) -> URLClassLoader,
) {
    constructor(
        applicationClassLoader: ClassLoader = FeaturePackEntrypoint::class.java.classLoader,
    ) : this(applicationClassLoader, { jarUrl, parent -> URLClassLoader(arrayOf(jarUrl), parent) })

    internal constructor(
        applicationClassLoader: ClassLoader,
        packClassLoaderFactory: FeaturePackClassLoaderFactory,
    ) : this(applicationClassLoader, packClassLoaderFactory::create)

    fun discover(developmentRoot: Path): FeaturePackDiscoveryReport {
        val normalizedRoot = developmentRoot.toAbsolutePath().normalize()
        if (!normalizedRoot.isDirectory()) {
            return FeaturePackDiscoveryReport(
                candidates = emptyList(),
                failures = listOf(FeaturePackFailure(
                    FeaturePackFailureKind.INVALID_DISCOVERY_PATH,
                    "Feature Pack development root is not a directory: $normalizedRoot",
                )),
            )
        }

        return try {
            val candidates = mutableListOf<LocalFeaturePackCandidate>()
            val failures = mutableListOf<FeaturePackFailure>()
            Files.list(normalizedRoot).use { children ->
                children.filter(Path::isDirectory)
                    .sorted()
                    .forEach { packDirectory ->
                        val jar = packDirectory.resolve(PACK_JAR_NAME)
                        if (jar.isRegularFile()) {
                            candidates += LocalFeaturePackCandidate(packDirectory, jar)
                        } else {
                            failures += FeaturePackFailure(
                                FeaturePackFailureKind.MISSING_JAR,
                                "Feature Pack directory does not contain $PACK_JAR_NAME: $packDirectory",
                            )
                        }
                    }
            }
            FeaturePackDiscoveryReport(candidates, failures)
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            FeaturePackDiscoveryReport(
                candidates = emptyList(),
                failures = listOf(FeaturePackFailure(
                    FeaturePackFailureKind.DISCOVERY_FAILED,
                    "Could not inspect Feature Pack development root: $normalizedRoot",
                    error,
                )),
            )
        }
    }

    fun load(
        candidate: LocalFeaturePackCandidate,
        contextFactory: FeaturePackContextFactory,
    ): FeaturePackLoadResult {
        val jar = candidate.jar.toAbsolutePath().normalize()
        if (!jar.isRegularFile()) {
            return failed(FeaturePackFailureKind.MISSING_JAR, "Feature Pack JAR does not exist: $jar")
        }
        val manifestMetadata = try {
            FeaturePackJarManifest.read(jar)
        } catch (error: IOException) {
            return failed(FeaturePackFailureKind.INVALID_JAR, "Feature Pack JAR is invalid: $jar", error)
        } catch (error: IllegalArgumentException) {
            return failed(
                FeaturePackFailureKind.INVALID_DESCRIPTOR,
                "Feature Pack metadata is invalid for $jar: ${error.message ?: error::class.simpleName}",
                error,
            )
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            return failed(FeaturePackFailureKind.INVALID_JAR, "Feature Pack JAR could not be inspected: $jar", error)
        }
        FeaturePackCompatibilityPolicy.incompatibilityMessage(
            manifestMetadata.requiredFeatureApiVersion,
        )?.let { message ->
            return failed(FeaturePackFailureKind.INCOMPATIBLE_FEATURE_API, message)
        }

        val packClassLoader = try {
            packClassLoaderFactory(
                jar.toUri().toURL(),
                SharedFeaturePackParentClassLoader(applicationClassLoader),
            )
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            return failed(
                FeaturePackFailureKind.CLASSLOADER_CREATION_FAILED,
                "Feature Pack ClassLoader creation failed: $jar",
                error,
            )
        }
        val entrypoint = when (val result = findEntrypoint(packClassLoader, jar)) {
            is EntrypointResult.Found -> result.entrypoint
            is EntrypointResult.Failed -> {
                packClassLoader.closeIgnoringFailure(result.failure.cause)
                return FeaturePackLoadResult.Failed(result.failure)
            }
        }
        val descriptor = try {
            entrypoint.descriptor()
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            packClassLoader.closeIgnoringFailure(error)
            return failed(FeaturePackFailureKind.DESCRIPTOR_FAILED, "Feature Pack descriptor failed: $jar", error)
        }
        val context = try {
            contextFactory.create(descriptor)
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            packClassLoader.closeIgnoringFailure(error)
            return failed(
                FeaturePackFailureKind.CONTEXT_CREATION_FAILED,
                "Feature Pack context creation failed for ${descriptor.packId}",
                error,
            )
        }
        val session = try {
            entrypoint.start(context)
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            (context as? FeaturePackContextLifecycle)?.closeHostResourcesIgnoringFailure(error)
            packClassLoader.closeIgnoringFailure(error)
            return failed(
                FeaturePackFailureKind.STARTUP_FAILED,
                "Feature Pack startup failed for ${descriptor.packId}",
                error,
            )
        }
        return FeaturePackLoadResult.Loaded(
            LoadedFeaturePack(descriptor, session, context as? FeaturePackContextLifecycle, packClassLoader),
        )
    }

    private fun findEntrypoint(classLoader: URLClassLoader, jar: Path): EntrypointResult = try {
        val entrypoints = ServiceLoader.load(FeaturePackEntrypoint::class.java, classLoader).toList()
        when (entrypoints.size) {
            0 -> EntrypointResult.Failed(FeaturePackFailure(
                FeaturePackFailureKind.ZERO_ENTRYPOINTS,
                "Feature Pack JAR declares no FeaturePackEntrypoint service: $jar",
            ))
            1 -> EntrypointResult.Found(entrypoints.single())
            else -> EntrypointResult.Failed(FeaturePackFailure(
                FeaturePackFailureKind.MULTIPLE_ENTRYPOINTS,
                "Feature Pack JAR declares ${entrypoints.size} entrypoints; exactly one is required: $jar",
            ))
        }
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        EntrypointResult.Failed(FeaturePackFailure(
            FeaturePackFailureKind.SERVICE_LOADING_FAILED,
            "Feature Pack service loading failed: $jar",
            error,
        ))
    }

    private fun failed(kind: FeaturePackFailureKind, message: String, cause: Throwable? = null) =
        FeaturePackLoadResult.Failed(FeaturePackFailure(kind, message, cause))

    private sealed interface EntrypointResult {
        data class Found(val entrypoint: FeaturePackEntrypoint) : EntrypointResult

        data class Failed(val failure: FeaturePackFailure) : EntrypointResult
    }
}

internal fun interface FeaturePackClassLoaderFactory {
    fun create(jarUrl: URL, parent: ClassLoader): URLClassLoader
}

class LoadedFeaturePack internal constructor(
    val descriptor: FeaturePackDescriptor,
    private val session: FeaturePackSession,
    private val contextLifecycle: FeaturePackContextLifecycle?,
    val classLoader: URLClassLoader,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun closeSafely(): FeaturePackCloseResult {
        if (!closed.compareAndSet(false, true)) return FeaturePackCloseResult.Closed

        var failure: Throwable? = null
        try {
            session.close()
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            failure = error
        }
        try {
            contextLifecycle?.closeHostResources()
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try {
            classLoader.close()
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        return failure?.let {
            FeaturePackCloseResult.Failed(FeaturePackFailure(
                FeaturePackFailureKind.CLOSE_FAILED,
                "Feature Pack close failed for ${descriptor.packId}",
                it,
            ))
        } ?: FeaturePackCloseResult.Closed
    }

    /** Close failures are contained; use [closeSafely] when the caller needs the diagnostic. */
    override fun close() {
        closeSafely()
    }
}

private fun FeaturePackContextLifecycle.closeHostResourcesIgnoringFailure(originalFailure: Throwable) {
    try {
        closeHostResources()
    } catch (closeFailure: Throwable) {
        rethrowIfFatal(closeFailure)
        originalFailure.addSuppressed(closeFailure)
    }
}

private class SharedFeaturePackParentClassLoader(
    private val applicationClassLoader: ClassLoader,
) : ClassLoader(ClassLoader.getPlatformClassLoader()) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val loaded = when {
            PLATFORM_PREFIXES.any(name::startsWith) -> super.loadClass(name, false)
            SHARED_PREFIXES.any(name::startsWith) -> applicationClassLoader.loadClass(name)
            else -> throw ClassNotFoundException("Class is outside the Feature Pack shared parent boundary: $name")
        }
        if (resolve) resolveClass(loaded)
        return loaded
    }

    private companion object {
        val PLATFORM_PREFIXES = listOf(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            // Public JDK callback-server API from the jdk.httpserver module.
            "com.sun.net.httpserver.",
        )
        val SHARED_PREFIXES = listOf("dev.evestaticmapplanner.feature.api.", "kotlin.")
    }
}

private fun URLClassLoader.closeIgnoringFailure(originalFailure: Throwable?) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        rethrowIfFatal(closeFailure)
        originalFailure?.addSuppressed(closeFailure)
    }
}

@Suppress("DEPRECATION")
internal fun rethrowIfFatal(error: Throwable) {
    if (error is VirtualMachineError || error is ThreadDeath) throw error
}

private const val PACK_JAR_NAME = "pack.jar"
