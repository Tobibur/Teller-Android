package com.levibostian.tellerexample.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

class RepoOwnerModel(@SerializedName("login") var name: String = "")