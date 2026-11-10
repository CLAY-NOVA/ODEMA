package ma.odema.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Lang { EN, FR, AR }

/** Morocco's 12 regions (2015 division), names in English, French and Arabic. */
data class Region(val number: Int, val en: String, val fr: String, val ar: String, val capitalId: String) {
    fun name(lang: Lang): String = when (lang) { Lang.EN -> en; Lang.FR -> fr; Lang.AR -> ar }
}

object MoroccoRegions {
    val all: List<Region> = listOf(
        Region(1, "Tanger-Tetouan-Al Hoceima", "Tanger-Tétouan-Al Hoceïma", "طنجة تطوان الحسيمة", "tangier"),
        Region(2, "Oriental", "L'Oriental", "الشرق", "oujda"),
        Region(3, "Fez-Meknes", "Fès-Meknès", "فاس مكناس", "fez"),
        Region(4, "Rabat-Sale-Kenitra", "Rabat-Salé-Kénitra", "الرباط سلا القنيطرة", "rabat"),
        Region(5, "Beni Mellal-Khenifra", "Béni Mellal-Khénifra", "بني ملال خنيفرة", "beni_mellal"),
        Region(6, "Casablanca-Settat", "Casablanca-Settat", "الدار البيضاء سطات", "casablanca"),
        Region(7, "Marrakesh-Safi", "Marrakech-Safi", "مراكش آسفي", "marrakesh"),
        Region(8, "Draa-Tafilalet", "Drâa-Tafilalet", "درعة تافيلالت", "errachidia"),
        Region(9, "Souss-Massa", "Souss-Massa", "سوس ماسة", "agadir"),
        Region(10, "Guelmim-Oued Noun", "Guelmim-Oued Noun", "كلميم واد نون", "guelmim"),
        Region(11, "Laayoune-Sakia El Hamra", "Laâyoune-Sakia El Hamra", "العيون الساقية الحمراء", "laayoune"),
        Region(12, "Dakhla-Oued Ed-Dahab", "Dakhla-Oued Ed-Dahab", "الداخلة وادي الذهب", "dakhla"),
    )

    fun byNumber(n: Int?): Region? = all.firstOrNull { it.number == n }
}

/** A built-in town. Coordinates and elevations come from GeoNames via Open-Meteo's geocoder. */
data class City(
    val id: String,
    val region: Int,
    val en: String,
    val fr: String,
    val ar: String,
    val lat: Double,
    val lon: Double,
    val elevation: Double,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
    fun name(lang: Lang): String = when (lang) { Lang.EN -> en; Lang.FR -> fr; Lang.AR -> ar }

    /** Matches a search in any of the three languages, ignoring accents and case. */
    fun matches(query: String): Boolean {
        val q = normalize(query)
        return q.isNotEmpty() && listOf(en, fr, ar).any { normalize(it).contains(q) }
    }
}

object MoroccoCities {
    val all: List<City> = listOf(
        City("tangier", 1, "Tangier", "Tanger", "طنجة", 35.7673, -5.7998, 20.0),
        City("tetouan", 1, "Tetouan", "Tétouan", "تطوان", 35.5784, -5.3684, 121.0),
        City("al_hoceima", 1, "Al Hoceima", "Al Hoceïma", "الحسيمة", 35.2516, -3.9372, 74.0),
        City("larache", 1, "Larache", "Larache", "العرائش", 35.1932, -6.1557, 38.0),
        City("ksar_el_kebir", 1, "Ksar El Kebir", "Ksar El Kébir", "القصر الكبير", 35.0004, -5.9038, 18.0),
        City("chefchaouen", 1, "Chefchaouen", "Chefchaouen", "شفشاون", 35.1688, -5.2636, 607.0),
        City("asilah", 1, "Asilah", "Asilah", "أصيلة", 35.4652, -6.0342, 17.0),
        City("ouazzane", 1, "Ouazzane", "Ouazzane", "وزان", 34.7958, -5.5785, 306.0),
        City("martil", 1, "Martil", "Martil", "مرتيل", 35.6166, -5.2752, 5.0),
        City("fnideq", 1, "Fnideq", "Fnideq", "الفنيدق", 35.8491, -5.3575, 21.0),
        City("m_diq", 1, "M'diq", "M'diq", "المضيق", 35.6831, -5.3287, 23.0),
        City("oujda", 2, "Oujda", "Oujda", "وجدة", 34.6814, -1.9086, 549.0),
        City("nador", 2, "Nador", "Nador", "الناظور", 35.1681, -2.9335, 19.0),
        City("berkane", 2, "Berkane", "Berkane", "بركان", 34.92, -2.32, 173.0),
        City("taourirt", 2, "Taourirt", "Taourirt", "تاوريرت", 34.4073, -2.8973, 394.0),
        City("guercif", 2, "Guercif", "Guercif", "جرسيف", 34.2257, -3.3536, 367.0),
        City("jerada", 2, "Jerada", "Jerada", "جرادة", 34.31, -2.16, 1045.0),
        City("figuig", 2, "Figuig", "Figuig", "فجيج", 32.1089, -1.2286, 898.0),
        City("bouarfa", 2, "Bouarfa", "Bouarfa", "بوعرفة", 32.5338, -1.9621, 1169.0),
        City("driouch", 2, "Driouch", "Driouch", "الدريوش", 34.977, -3.379, 287.0),
        City("saidia", 2, "Saidia", "Saïdia", "السعيدية", 35.0819, -2.229, 4.0),
        City("zaio", 2, "Zaio", "Zaïo", "زايو", 34.9428, -2.7329, 198.0),
        City("fez", 3, "Fez", "Fès", "فاس", 34.0331, -5.0003, 406.0),
        City("meknes", 3, "Meknes", "Meknès", "مكناس", 33.8935, -5.5473, 531.0),
        City("taza", 3, "Taza", "Taza", "تازة", 34.21, -4.01, 530.0),
        City("ifrane", 3, "Ifrane", "Ifrane", "إفران", 33.5267, -5.1102, 1648.0),
        City("sefrou", 3, "Sefrou", "Sefrou", "صفرو", 33.8319, -4.828, 809.0),
        City("azrou", 3, "Azrou", "Azrou", "أزرو", 33.4344, -5.2213, 1277.0),
        City("el_hajeb", 3, "El Hajeb", "El Hajeb", "الحاجب", 33.6879, -5.371, 1023.0),
        City("taounate", 3, "Taounate", "Taounate", "تاونات", 34.5366, -4.6401, 600.0),
        City("missour", 3, "Missour", "Missour", "ميسور", 33.0489, -3.9895, 883.0),
        City("boulemane", 3, "Boulemane", "Boulemane", "بولمان", 33.3638, -4.729, 1712.0),
        City("imouzzer_kandar", 3, "Imouzzer Kandar", "Imouzzer Kandar", "إيموزار كندر", 33.7287, -5.0109, 1352.0),
        City("rabat", 4, "Rabat", "Rabat", "الرباط", 34.0132, -6.8326, 46.0),
        City("sale", 4, "Sale", "Salé", "سلا", 34.0531, -6.7985, 34.0),
        City("kenitra", 4, "Kenitra", "Kénitra", "القنيطرة", 34.261, -6.5802, 13.0),
        City("temara", 4, "Temara", "Témara", "تمارة", 33.9287, -6.9066, 73.0),
        City("skhirat", 4, "Skhirat", "Skhirat", "الصخيرات", 33.8527, -7.0317, 51.0),
        City("khemisset", 4, "Khemisset", "Khémisset", "الخميسات", 33.824, -6.0663, 451.0),
        City("sidi_kacem", 4, "Sidi Kacem", "Sidi Kacem", "سيدي قاسم", 34.2215, -5.7077, 83.0),
        City("sidi_slimane", 4, "Sidi Slimane", "Sidi Slimane", "سيدي سليمان", 34.2648, -5.926, 37.0),
        City("tiflet", 4, "Tiflet", "Tiflet", "تيفلت", 33.8947, -6.3065, 340.0),
        City("rommani", 4, "Rommani", "Rommani", "الرماني", 33.5297, -6.6042, 330.0),
        City("beni_mellal", 5, "Beni Mellal", "Béni Mellal", "بني ملال", 32.3372, -6.3498, 538.0),
        City("khenifra", 5, "Khenifra", "Khénifra", "خنيفرة", 32.9349, -5.6617, 868.0),
        City("khouribga", 5, "Khouribga", "Khouribga", "خريبكة", 32.8811, -6.9063, 804.0),
        City("azilal", 5, "Azilal", "Azilal", "أزيلال", 31.9616, -6.5711, 1357.0),
        City("kasba_tadla", 5, "Kasba Tadla", "Kasba Tadla", "قصبة تادلة", 32.5977, -6.2684, 499.0),
        City("oued_zem", 5, "Oued Zem", "Oued Zem", "وادي زم", 32.8627, -6.5736, 783.0),
        City("demnate", 5, "Demnate", "Demnate", "دمنات", 31.7344, -7.005, 927.0),
        City("fquih_ben_salah", 5, "Fquih Ben Salah", "Fquih Ben Salah", "الفقيه بن صالح", 32.5021, -6.6877, 444.0),
        City("casablanca", 6, "Casablanca", "Casablanca", "الدار البيضاء", 33.5883, -7.6114, 27.0),
        City("mohammedia", 6, "Mohammedia", "Mohammedia", "المحمدية", 33.6861, -7.383, 29.0),
        City("el_jadida", 6, "El Jadida", "El Jadida", "الجديدة", 33.2568, -8.5088, 10.0),
        City("settat", 6, "Settat", "Settat", "سطات", 33.001, -7.6166, 365.0),
        City("berrechid", 6, "Berrechid", "Berrechid", "برشيد", 33.2655, -7.5875, 220.0),
        City("benslimane", 6, "Benslimane", "Benslimane", "بنسليمان", 33.616, -7.1215, 270.0),
        City("azemmour", 6, "Azemmour", "Azemmour", "أزمور", 33.2895, -8.3425, 28.0),
        City("sidi_bennour", 6, "Sidi Bennour", "Sidi Bennour", "سيدي بنور", 32.6524, -8.4277, 182.0),
        City("bouznika", 6, "Bouznika", "Bouznika", "بوزنيقة", 33.7894, -7.1597, 43.0),
        City("mediouna", 6, "Mediouna", "Médiouna", "مديونة", 33.4516, -7.5199, 172.0),
        City("dar_bouazza", 6, "Dar Bouazza", "Dar Bouazza", "دار بوعزة", 33.5153, -7.8168, 21.0),
        City("marrakesh", 7, "Marrakesh", "Marrakech", "مراكش", 31.6342, -7.9999, 457.0),
        City("safi", 7, "Safi", "Safi", "آسفي", 32.2994, -9.2372, 45.0),
        City("essaouira", 7, "Essaouira", "Essaouira", "الصويرة", 31.5125, -9.77, 7.0),
        City("el_kelaa_des_sraghna", 7, "El Kelaa des Sraghna", "El Kelâa des Sraghna", "قلعة السراغنة", 32.0512, -7.4108, 471.0),
        City("youssoufia", 7, "Youssoufia", "Youssoufia", "اليوسفية", 32.2463, -8.5294, 302.0),
        City("ben_guerir", 7, "Ben Guerir", "Ben Guerir", "ابن جرير", 32.2409, -7.954, 474.0),
        City("chichaoua", 7, "Chichaoua", "Chichaoua", "شيشاوة", 31.5435, -8.7628, 356.0),
        City("amizmiz", 7, "Amizmiz", "Amizmiz", "أمزميز", 31.2167, -8.2318, 979.0),
        City("imlil", 7, "Imlil", "Imlil", "إمليل", 31.1273, -7.9189, 1899.0),
        City("oukaimeden", 7, "Oukaimeden", "Oukaïmeden", "أوكايمدن", 31.206, -7.8609, 2637.0),
        City("setti_fatma", 7, "Setti Fatma (Ourika)", "Setti Fatma (Ourika)", "ستي فاطمة", 31.2251, -7.6775, 1434.0),
        City("tahannaout", 7, "Tahannaout", "Tahannaout", "تحناوت", 31.3524, -7.9501, 942.0),
        City("errachidia", 8, "Errachidia", "Errachidia", "الرشيدية", 31.9314, -4.4266, 1039.0),
        City("ouarzazate", 8, "Ouarzazate", "Ouarzazate", "ورزازات", 30.9189, -6.8934, 1113.0),
        City("midelt", 8, "Midelt", "Midelt", "ميدلت", 32.6852, -4.7451, 1462.0),
        City("tinghir", 8, "Tinghir", "Tinghir", "تنغير", 31.5147, -5.5328, 1281.0),
        City("zagora", 8, "Zagora", "Zagora", "زاكورة", 30.3324, -5.8384, 733.0),
        City("erfoud", 8, "Erfoud", "Erfoud", "أرفود", 31.4353, -4.2326, 809.0),
        City("merzouga", 8, "Merzouga", "Merzouga", "مرزوكة", 31.0991, -4.013, 699.0),
        City("boumalne_dades", 8, "Boumalne Dades", "Boumalne Dadès", "بومالن دادس", 31.3799, -5.9882, 1539.0),
        City("goulmima", 8, "Goulmima", "Goulmima", "كلميمة", 31.6923, -4.9526, 1028.0),
        City("agdz", 8, "Agdz", "Agdz", "أكدز", 30.6936, -6.4463, 940.0),
        City("rissani", 8, "Rissani", "Rissani", "الريصاني", 31.2826, -4.2639, 760.0),
        City("kelaat_m_gouna", 8, "Kelaat M'Gouna", "Kelâat M'Gouna", "قلعة مكونة", 31.2438, -6.1286, 1460.0),
        City("agadir", 9, "Agadir", "Agadir", "أكادير", 30.4202, -9.5982, 31.0),
        City("inezgane", 9, "Inezgane", "Inezgane", "إنزكان", 30.3554, -9.5364, 31.0),
        City("ait_melloul", 9, "Ait Melloul", "Aït Melloul", "أيت ملول", 30.3416, -9.5036, 20.0),
        City("taroudant", 9, "Taroudant", "Taroudant", "تارودانت", 30.4703, -8.877, 237.0),
        City("tiznit", 9, "Tiznit", "Tiznit", "تيزنيت", 29.6974, -9.7316, 240.0),
        City("tata", 9, "Tata", "Tata", "طاطا", 29.7441, -7.973, 684.0),
        City("ouled_teima", 9, "Ouled Teima", "Ouled Teïma", "أولاد تايمة", 30.3947, -9.209, 129.0),
        City("biougra", 9, "Biougra", "Biougra", "بيوكرى", 30.2179, -9.3702, 117.0),
        City("taghazout", 9, "Taghazout", "Taghazout", "تغازوت", 30.544, -9.707, 1.0),
        City("tafraout", 9, "Tafraout", "Tafraout", "تافراوت", 29.7245, -8.9747, 993.0),
        City("aourir", 9, "Aourir", "Aourir", "أورير", 30.4924, -9.6355, 121.0),
        City("guelmim", 10, "Guelmim", "Guelmim", "كلميم", 28.987, -10.0574, 301.0),
        City("tan_tan", 10, "Tan-Tan", "Tan-Tan", "طانطان", 28.438, -11.1032, 45.0),
        City("sidi_ifni", 10, "Sidi Ifni", "Sidi Ifni", "سيدي إفني", 29.3798, -10.173, 44.0),
        City("assa", 10, "Assa", "Assa", "آسا", 28.6099, -9.4299, 318.0),
        City("bouizakarne", 10, "Bouizakarne", "Bouizakarne", "بويزكارن", 29.1771, -9.7118, 635.0),
        City("laayoune", 11, "Laayoune", "Laâyoune", "العيون", 27.1418, -13.188, 68.0),
        City("boujdour", 11, "Boujdour", "Boujdour", "بوجدور", 26.1307, -14.4851, 28.0),
        City("smara", 11, "Smara", "Es-Semara", "السمارة", 26.7384, -11.6719, 188.0),
        City("tarfaya", 11, "Tarfaya", "Tarfaya", "طرفاية", 27.9392, -12.926, 5.0),
        City("el_marsa", 11, "El Marsa", "El Marsa", "المرسى", 27.0961, -13.4158, 4.0),
        City("dakhla", 12, "Dakhla", "Dakhla", "الداخلة", 23.6848, -15.958, 6.0),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): City? = id?.let(byId::get)

    fun inRegion(region: Int): List<City> = all.filter { it.region == region }

    fun search(query: String): List<City> = all.filter { it.matches(query) }

    /** Closest built-in town and its distance in km. */
    fun nearest(point: GeoPoint): Pair<City, Double> =
        all.map { it to distanceKm(point, it.point) }.minBy { it.second }
}

/** Generous box around Morocco including the southern provinces; used to keep the app on Morocco. */
object MoroccoBounds {
    const val NORTH = 36.0
    const val SOUTH = 20.7
    const val WEST = -17.2
    const val EAST = -0.95
    val center = GeoPoint(31.6, -7.1)

    fun contains(p: GeoPoint): Boolean = p.lat in SOUTH..NORTH && p.lon in WEST..EAST
}

fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

internal fun normalize(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه')
        .trim()
