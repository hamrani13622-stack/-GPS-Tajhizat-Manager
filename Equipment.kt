package ir.gps.tajhizat

data class Equipment(
    val code: String,
    val name: String = "",
    val type: String = "",
    val city: String = "",
    val address: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val status: String = "",
    val visitDate: String = "",
    val notes: String = "",
    val actions: String = ""
)
