/*
 * Module: r2-lcp-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.lcp.model.sub.lcp

import org.json.JSONObject

class User(json: JSONObject) {
    var id: String = json.getString("id")
//    var email: String = json.getString("email")
//    var name: String = json.getString("name")
    private var encrypted = mutableListOf<String>()

    init {
        if (json.has("encrypted")) {
            val encryptedArray = json.getJSONArray("encrypted")
            for (i in 0 until encryptedArray.length()){
                encrypted.add(encryptedArray.getString(i))
            }
        }
    }
}