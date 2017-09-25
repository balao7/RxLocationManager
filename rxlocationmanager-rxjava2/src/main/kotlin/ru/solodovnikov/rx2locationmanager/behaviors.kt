package ru.solodovnikov.rx2locationmanager

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import java.util.*

interface SingleBehavior {
    fun <T> transform(upstream: Single<T>): Single<T>
}

interface MaybeBehavior {
    fun <T> transform(upstream: Maybe<T>): Maybe<T>
}

interface ObservableBehavior {
    fun <T> transform(upstream: Observable<T>): Observable<T>
}

interface CompletableBehavior {
    fun transform(upstream: Completable): Completable
}

interface Behavior : SingleBehavior, MaybeBehavior, ObservableBehavior, CompletableBehavior


/**
 * Transformer used to request runtime permissions
 *
 * Call [RxLocationManager.onRequestPermissionsResult] inside your [android.app.Activity.onRequestPermissionsResult]
 * to get request permissions results in the transformer.
 */
open class PermissionBehavior(context: Context,
                              private val rxLocationManager: RxLocationManager,
                              callback: BasePermissionBehavior.PermissionCallback
) : BasePermissionBehavior(context, callback), Behavior {

    override fun <T> transform(upstream: Single<T>): Single<T> =
            checkPermissions().andThen(upstream)

    override fun <T> transform(upstream: Maybe<T>): Maybe<T> =
            checkPermissions().andThen(upstream)

    override fun <T> transform(upstream: Observable<T>): Observable<T> =
            checkPermissions().andThen(upstream)

    override fun transform(upstream: Completable): Completable =
            checkPermissions().andThen(upstream)

    /**
     * Construct [Completable] which check runtime permissions
     */
    protected open fun checkPermissions(): Completable =
            Completable.create { emitter ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val deniedPermissions = getDeniedPermissions()

                    if (deniedPermissions.isNotEmpty()) {
                        //wait until user approve permissions or dispose action
                        subscribeToPermissionUpdate {
                            val resultPermissions = it.first
                            val resultPermissionsResults = it.second
                            if (!Arrays.equals(resultPermissions, deniedPermissions) ||
                                    resultPermissionsResults.find { it == PackageManager.PERMISSION_DENIED } != null) {
                                emitter.onError(SecurityException("User denied permissions: ${deniedPermissions.asList()}"))
                            } else {
                                emitter.onComplete()
                            }
                        }.apply { emitter.setCancellable { dispose() } }

                        callback.requestPermissions(deniedPermissions)
                    } else {
                        emitter.onComplete()
                    }
                } else {
                    emitter.onComplete()
                }
            }

    /**
     * Subscribe to request permissions result
     */
    protected fun subscribeToPermissionUpdate(onUpdate: (Pair<Array<out String>, IntArray>) -> Unit): Disposable =
            rxLocationManager.subscribeToPermissionUpdate(onUpdate)
}

/**
 * Transformer used to ignore any described error type.
 *
 * @param errorsToIgnore if empty, then ignore all errors, otherwise just described types.
 */
class IgnoreErrorBehavior(vararg errorsToIgnore: Class<out Throwable>) : Behavior {
    private val toIgnore: Array<out Class<out Throwable>> = errorsToIgnore

    override fun <T> transform(upstream: Single<T>): Single<T> =
            upstream.onErrorResumeNext {
                if (toIgnore.isEmpty() || toIgnore.contains(it.javaClass)) {
                    IgnorableException()
                } else {
                    it
                }.let { Single.error<T>(it) }
            }

    override fun <T> transform(upstream: Maybe<T>): Maybe<T> =
            upstream.onErrorResumeNext { t: Throwable ->
                if (toIgnore.isEmpty() || toIgnore.contains(t.javaClass)) {
                    Maybe.empty()
                } else {
                    Maybe.error(t)
                }
            }

    override fun <T> transform(upstream: Observable<T>): Observable<T> =
            upstream.onErrorResumeNext { t: Throwable ->
                if (toIgnore.isEmpty() || toIgnore.contains(t.javaClass)) {
                    Observable.empty()
                } else {
                    Observable.error(t)
                }
            }

    override fun transform(upstream: Completable): Completable =
            upstream.onErrorResumeNext {
                if (toIgnore.isEmpty() || toIgnore.contains(it.javaClass)) {
                    Completable.complete()
                } else {
                    Completable.error(it)
                }
            }
}