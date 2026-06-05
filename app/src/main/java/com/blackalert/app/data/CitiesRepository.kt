package com.blackalert.app.data

import android.content.Context
import org.json.JSONObject

/** עיר מתוך cities.json */
data class CityInfo(
    val key: String,   // השם כפי שמופיע ב-event.cities (מפתח ב-cities.json)
    val id: Int,
    val areaId: Int,
    val he: String,
    val lat: Double,
    val lng: Double
)

data class AreaInfo(val id: Int, val he: String)

/**
 * מאגר ערים/אזורים — מקביל ל-City.js.
 * נטען מ-assets (snapshot מצורף) ומתרענן מהשרת ל-filesDir כשיש גרסה חדשה.
 */
class CitiesRepository private constructor(
    val cities: Map<String, CityInfo>,
    val areas: Map<Int, AreaInfo>
) {
    fun cityByKey(key: String): CityInfo? = cities[key]
    fun cityById(id: Int): CityInfo? = cities.values.firstOrNull { it.id == id }
    fun allCitiesSorted(): List<CityInfo> = cities.values.sortedBy { it.he }
    fun allAreasSorted(): List<AreaInfo> = areas.values.sortedBy { it.he }

    companion object {
        @Volatile private var instance: CitiesRepository? = null
        private const val CACHE_FILE = "cities.json"

        fun get(context: Context): CitiesRepository {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val json = readCacheOrAsset(context)
                val repo = parse(json)
                instance = repo
                return repo
            }
        }

        /** נקרא ע"י ה-API אחרי הורדת גרסה חדשה — מעדכן את ה-cache ואת המופע בזיכרון. */
        fun updateFromServer(context: Context, json: String): CitiesRepository {
            context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use {
                it.write(json.toByteArray(Charsets.UTF_8))
            }
            val repo = parse(json)
            instance = repo
            return repo
        }

        private fun readCacheOrAsset(context: Context): String {
            val cache = context.getFileStreamPath(CACHE_FILE)
            if (cache != null && cache.exists() && cache.length() > 0) {
                return cache.readText(Charsets.UTF_8)
            }
            return context.assets.open(CACHE_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        private fun parse(json: String): CitiesRepository {
            val root = JSONObject(json)
            val citiesObj = root.optJSONObject("cities") ?: JSONObject()
            val areasObj = root.optJSONObject("areas") ?: JSONObject()

            val cities = HashMap<String, CityInfo>()
            val cityKeys = citiesObj.keys()
            while (cityKeys.hasNext()) {
                val key = cityKeys.next()
                val c = citiesObj.optJSONObject(key) ?: continue
                cities[key] = CityInfo(
                    key = key,
                    id = c.optInt("id", -1),
                    areaId = c.optInt("area", -1),
                    he = c.optString("he", key),
                    lat = c.optDouble("lat", 0.0),
                    lng = c.optDouble("lng", 0.0)
                )
            }

            val areas = HashMap<Int, AreaInfo>()
            val areaKeys = areasObj.keys()
            while (areaKeys.hasNext()) {
                val key = areaKeys.next()
                val a = areasObj.optJSONObject(key) ?: continue
                val id = key.toIntOrNull() ?: continue
                areas[id] = AreaInfo(id = id, he = a.optString("he", key))
            }
            return CitiesRepository(cities, areas)
        }
    }
}
