package com.dakyub.crunchymal

import android.app.Application

class CrunchyMalApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}
