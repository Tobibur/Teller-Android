package com.levibostian.tellerexample.model

import androidx.room.*

@Entity(tableName = "repo")
class RepoModel(@PrimaryKey var id: Long = 0,
                var name: String = "",
                @Embedded(prefix = "repo_owner_") var owner: RepoOwnerModel = RepoOwnerModel())