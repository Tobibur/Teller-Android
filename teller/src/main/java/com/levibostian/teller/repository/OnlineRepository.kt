package com.levibostian.teller.repository

import android.annotation.SuppressLint
import com.levibostian.teller.Teller
import com.levibostian.teller.datastate.OnlineDataState
import com.levibostian.teller.subject.OnlineDataStateBehaviorSubject
import com.levibostian.teller.type.AgeOfData
import com.levibostian.teller.util.ConstantsUtil
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.util.*

abstract class OnlineRepository<RESULT: Any, GET_DATA_REQUIREMENTS: GetDataRequirements, REQUEST: Any> {

    private val forceSyncNextTimeFetchKey by lazy {
        "${ConstantsUtil.PREFIX}_forceSyncNextTimeFetch_dataSource_${this::class.java.simpleName}_key"
    }

    companion object {
        const val INVALID_LAST_FETCHED_VALUE: Long = -1L
    }

    private var compositeDisposable = CompositeDisposable()
    private var stateOfDate: OnlineDataStateBehaviorSubject<RESULT>? = null

    /**
     * If your repository has some special ID or something to make it unique compared to other instances of your repository, set this tag.
     *
     * This tag is used to keep track of the [Date] that data was last saved to determine how old your data is. If you do not set this [tag] property, all instances of your [LocalRepository] subclass will have the same [Date].
     */
    open val tag: String = "(none)"

    private val dataLastSavedKey by lazy {
        "${ConstantsUtil.PREFIX}_${this::class.java.simpleName}_${tag}_LAST_SAVED_KEY"
    }

    /**
     * Keeps track of how old your data is.
     */
    private var timeSavedData: Date
        get() {
            return Date(Teller.shared.sharedPreferences.getLong(dataLastSavedKey, Date().time))
        }
        @SuppressLint("ApplySharedPref")
        set(value) {
            Teller.shared.sharedPreferences.edit().putLong(dataLastSavedKey, value.time).commit()
        }

    /**
     * Used to set how old data can be in the app for this certain type of data before it is considered too old and new data should be fetched.
     */
    abstract var maxAgeOfData: AgeOfData

    /**
     * Requirements needed to be able to load data in the [OnlineRepository].
     *
     * When this property is set, the [OnlineRepository] instance will begin to observe the data by loading the cached data and checking if it needs to fetch fresh.
     *
     * @see sync On how to force the data source to fetch fresh data if you want that after setting [loadDataRequirements].
     */
    protected var loadDataRequirements: GET_DATA_REQUIREMENTS? = null
        set(value) {
            beginObservingStateOfData()
        }

    var lastTimeFetchedFreshData: Date? = null
        get() {
            val loadDataRequirements = this.loadDataRequirements ?: return null

            val lastFetchedTime = Teller.shared.sharedPreferences.getLong(loadDataRequirements.tag, INVALID_LAST_FETCHED_VALUE)
            if (lastFetchedTime <= INVALID_LAST_FETCHED_VALUE) return null

            return Date(lastFetchedTime)
        }

    private fun beginObservingStateOfData() {
        loadDataRequirements?.let { getDataRequirements ->
            fun initializeObservingCachedData() {
                compositeDisposable.add(
                        this.observeCachedData(getDataRequirements)
                                .subscribe({ cachedData ->
                                    val needsToFetchFreshData = this.doSyncNextTimeFetched() || this.isDataTooOld()

                                    if (cachedData == null || isDataEmpty(cachedData)) {
                                        stateOfDate?.onNextEmpty(needsToFetchFreshData)
                                    } else {
                                        stateOfDate?.onNextData(cachedData, timeSavedData, needsToFetchFreshData)
                                    }

                                    if (needsToFetchFreshData) {
                                        this._sync(getDataRequirements, {
                                            stateOfDate?.onNextDoneFetchingFreshData(null)
                                        }, { error ->
                                            stateOfDate?.onNextDoneFetchingFreshData(error)
                                        })
                                    }
                                }, { error ->
                                    this.stateOfDate?.onNextError(error)
                                })
                )
            }

            if (!hasEverFetchedData()) {
                stateOfDate?.onNextFirstFetchOfData()

                this._sync(getDataRequirements, {
                    initializeObservingCachedData()
                }, { error ->
                    // Note: Even if there is an error, we want to start observing cached data so we can transition to an empty state instead of infinite loading state for the UI for the user.
                    initializeObservingCachedData()
                })
            } else {
                initializeObservingCachedData()
            }
        }
    }

    fun observe(): Observable<OnlineDataState<RESULT>> {
        if (stateOfDate == null) {
            stateOfDate = OnlineDataStateBehaviorSubject()
        }
        beginObservingStateOfData()

        return stateOfDate!!.asObservable().doOnDispose {
            compositeDisposable.dispose()
        }
    }

    /**
     * Perform a one time sync of the data. Sync will check the last updated date and the allowed age of data to determine if it should fetch fresh data or not. You may override this behavior with `force`.
     *
     * Note: If [loadDataRequirements] is null, [Completable.complete] will be returned from this function and it will be like the sync never happened.
     */
    fun sync(force: Boolean): Completable {
        val getDataRequirements = this.loadDataRequirements ?: return Completable.complete()

        return if (force || this.doSyncNextTimeFetched() || this.isDataTooOld()) {
            Completable.create { observer ->
                this._sync(getDataRequirements, {
                    observer.onComplete()
                }, { error ->
                    observer.onError(error)
                })
            }
        } else {
            Completable.complete()
        }
    }

    private fun _sync(getDataRequirements: GET_DATA_REQUIREMENTS, onComplete: () -> Unit, onError: (Throwable) -> Unit) {
        this.fetchFreshData(getDataRequirements)
                .subscribe({ freshData ->
                    this.resetForceSyncNextTimeFetched()
                    this.updateLastTimeFreshDataFetched(getDataRequirements)
                    this.saveData(freshData)
                            .subscribe({
                                onComplete()
                            }, { error ->
                                this.stateOfDate?.onNextError(error)
                                onError(error)
                            })
                }, { error ->
                    this.resetForceSyncNextTimeFetched()
                    // Note: We need to set the last updated time here or else we could run an infinite loop if the api call errors.
                    // The way that I handle errors now: if an error occurs (network error, status code error, any other error) I tell the rxswift subscriber that the error has occurred so you can tell the user. From there, you have the option to ask the user to retry the network call and perform the retry by calling datasource set data query requirements with force param true.
                    this.updateLastTimeFreshDataFetched(getDataRequirements)
                    this.stateOfDate?.onNextError(error)
                    onError(error)
                })
    }

    private fun getDateFromAllowedAgeOfDate(ageOfDate: AgeOfData): Date {
        return ageOfDate.toDate()
    }

    private fun isDataTooOld(): Boolean {
        if (!hasEverFetchedData()) return true

        return isDataOlderThan(getDateFromAllowedAgeOfDate(maxAgeOfData))
    }

    @SuppressLint("ApplySharedPref")
    private fun updateLastTimeFreshDataFetched(getDataRequirements: GET_DATA_REQUIREMENTS) {
        Teller.shared.sharedPreferences.edit().putLong(getDataRequirements.tag, Date().time).commit()
    }

    @SuppressLint("ApplySharedPref")
    private fun resetForceSyncNextTimeFetched() {
        Teller.shared.sharedPreferences.edit().putBoolean(forceSyncNextTimeFetchKey, false).commit()
    }

    private fun doSyncNextTimeFetched(): Boolean {
        return Teller.shared.sharedPreferences.getBoolean(forceSyncNextTimeFetchKey, false)
    }

    /**
     * Call if you want to flag the [Repository] to force sync the next time that it needs to sync data.
     */
    @SuppressLint("ApplySharedPref")
    fun forceSyncNextTimeFetched() {
        Teller.shared.sharedPreferences.edit().putBoolean(forceSyncNextTimeFetchKey, true).commit()
    }

    private fun hasEverFetchedData(): Boolean {
        return lastTimeFetchedFreshData != null
    }

    private fun isDataOlderThan(date: Date): Boolean {
        val lastTimeFetchedData = lastTimeFetchedFreshData ?: return true
        return lastTimeFetchedData < date
    }

    /**
     * Repository does what it needs in order to fetch fresh data. Probably call network API.
     */
    abstract fun fetchFreshData(requirements: GET_DATA_REQUIREMENTS): Single<REQUEST>

    /**
     * Save the data to whatever storage method Repository chooses.
     */
    protected abstract fun saveData(data: REQUEST?): Completable

    /**
     * Save the data to whatever storage method Repository chooses.
     *
     * It is up to you to call [saveData] when you have new data to save. A good place to do this is in a ViewModel.
     */
    fun save(data: REQUEST?): Completable {
        return Completable.fromAction { timeSavedData = Date() }
                .andThen(saveData(data))
                .subscribeOn(Schedulers.io())
    }

    /**
     * Get existing cached data saved to the device if it exists. Return nil is data does not exist or is empty.
     */
    abstract fun observeCachedData(requirements: GET_DATA_REQUIREMENTS): Observable<RESULT?>

    /**
     * DataType determines if data is empty or not. Because data can be of `Any` type, the DataType must determine when data is empty or not.
     */
    abstract fun isDataEmpty(data: RESULT): Boolean

}