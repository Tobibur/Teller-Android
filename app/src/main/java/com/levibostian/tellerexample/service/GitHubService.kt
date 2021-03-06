package com.levibostian.tellerexample.service

import com.levibostian.tellerexample.model.RepoModel
import io.reactivex.Single
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface GitHubService {

    @GET("users/{user}/repos")
    fun listRepos(@Path("user") user: String): Single<Response<List<RepoModel>>>

}