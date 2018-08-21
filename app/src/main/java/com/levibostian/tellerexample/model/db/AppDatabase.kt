package com.levibostian.tellerexample.model.db

import android.app.Application
import androidx.room.RoomDatabase
import androidx.room.Database
import androidx.room.Room
import com.levibostian.tellerexample.dao.ReposDao
import com.levibostian.tellerexample.model.RepoModel

@Database(entities = [RepoModel::class], version = 1, exportSchema = true)
abstract class AppDatabase: RoomDatabase() {
    abstract fun reposDao(): ReposDao
}
