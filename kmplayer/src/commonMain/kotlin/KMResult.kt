package dev.brahmkshatriya.kmplayer

public data class KMPlayerError(
    val code: String,
    val message: String,
    val cause: Throwable? = null,
)

public sealed interface KMResult<out T> {
    public data class Success<T>(val value: T) : KMResult<T>
    public data class Failure(val error: KMPlayerError) : KMResult<Nothing>
}

public fun <T> KMResult<T>.getOrThrow(): T = when (this) {
    is KMResult.Success -> value
    is KMResult.Failure -> throw IllegalStateException("${error.code}: ${error.message}", error.cause)
}

public inline fun <T, R> KMResult<T>.map(transform: (T) -> R): KMResult<R> = when (this) {
    is KMResult.Success -> KMResult.Success(transform(value))
    is KMResult.Failure -> this
}
