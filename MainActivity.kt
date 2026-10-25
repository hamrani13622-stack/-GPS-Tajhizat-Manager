package ir.gps.tajhizat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : Activity() {
    private lateinit var db: EquipmentDb
    private lateinit var map: MapView
    private lateinit var countText: TextView
    private var selectedCity = "همه شهرها"
    private val importXlsx = 101
    private val exportXlsx = 102
    private val locationRequest = 103
    private var myLocation: GeoPoint? = null
    private var locationMarker: Marker? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        db = EquipmentDb(this)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(12,12,12,12) }
        val title = TextView(this).apply { text="مدیریت تجهیزات GPS"; textSize=22f; gravity=Gravity.CENTER; setPadding(4,8,4,12) }
        root.addView(title)
        countText = TextView(this).apply { textSize=14f; setPadding(4,4,4,8) }; root.addView(countText)
        val search = EditText(this).apply { hint="جست‌وجو: کد، نام یا شهر"; setSingleLine(true) }
        root.addView(search)
        val citySpinner = Spinner(this)
        root.addView(citySpinner)
        val buttons = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun button(label:String, action:()->Unit) { val b=Button(this).apply { text=label; setOnClickListener { action() } }; buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f)) }
        button("افزودن") { editDialog(null) }
        button("ورود Excel") { pick(importXlsx, arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/octet-stream")) }
        button("خروجی Excel") { pickCreate() }
        root.addView(buttons)
        val myLocButton = Button(this).apply {
            text = "نمایش موقعیت من روی نقشه"
            setOnClickListener { showMyLocation() }
        }
        root.addView(myLocButton, LinearLayout.LayoutParams(-1, -2))
        map = MapView(this).apply { setMultiTouchControls(true); controller.setZoom(5.0); controller.setCenter(GeoPoint(32.5,53.0)) }
        root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
        val bottom = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val edit = Button(this).apply { text="ویرایش انتخاب‌شده"; setOnClickListener { selected?.let { editDialog(it) } ?: toast("ابتدا یک تجهیز را از نقشه انتخاب کنید") } }
        val neshan = Button(this).apply { text="نمایش در نشان"; setOnClickListener { selected?.let { openNeshan(it) } ?: toast("ابتدا یک تجهیز را انتخاب کنید") } }
        bottom.addView(edit, LinearLayout.LayoutParams(0,-2,1f)); bottom.addView(neshan, LinearLayout.LayoutParams(0,-2,1f)); root.addView(bottom)
        setContentView(root)
        citySpinner.onItemSelectedListener = object: AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { selectedCity=citySpinner.selectedItem?.toString() ?: "همه شهرها"; refresh(search.text.toString()) }
        }
        search.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?, start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?, start:Int,before:Int,count:Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        refresh()
        refreshCities(citySpinner)
    }
    private var selected: Equipment? = null
    private fun refreshCities(spinner:Spinner) {
        val items=listOf("همه شهرها")+db.cities()
        spinner.adapter=ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
    }
    private fun refresh(query:String="") {
        val rows=db.all(selectedCity,query)
        countText.text="تعداد کل: ${db.count()}  |  نمایش روی نقشه: ${rows.size}"
        map.overlays.clear()
        rows.forEach { e ->
            val m=Marker(map); m.position=GeoPoint(e.lat,e.lon); m.title="${e.code} - ${e.name}"; m.snippet="${e.city} | ${e.status}\n${e.notes}"
            m.setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
            m.setOnMarkerClickListener { marker, _ ->
                selected=e
                AlertDialog.Builder(this).setTitle("${e.code} - ${e.name}").setMessage("شهر: ${e.city}\nنوع: ${e.type}\nنشانی: ${e.address}\nوضعیت: ${e.status}\nتوضیحات: ${e.notes}")
                    .setPositiveButton("ویرایش") { _,_->editDialog(e) }.setNeutralButton("نمایش در نشان") { _,_->openNeshan(e) }.setNegativeButton("بستن",null).show()
                true
            }
            map.overlays.add(m)
        }
        myLocation?.let { point ->
            val marker = Marker(map)
            marker.position = point
            marker.title = "موقعیت من"
            marker.snippet = "موقعیت فعلی گوشی"
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(marker)
            locationMarker = marker
        }
        map.invalidate()
    }

    private fun showMyLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), locationRequest)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstNotNullOfOrNull { provider ->
                    try { if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null }
                    catch (_: SecurityException) { null }
                }
            if (last != null) {
                updateMyLocation(last)
                return
            }
            val provider = if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try { manager.removeUpdates(this) } catch (_: Exception) {}
                    updateMyLocation(location)
                }
            }
            manager.requestLocationUpdates(provider, 1000L, 0f, listener)
            Toast.makeText(this, "در حال دریافت موقعیت؛ GPS و مکان‌یابی گوشی را روشن کنید", Toast.LENGTH_LONG).show()
            window.decorView.postDelayed({
                try { manager.removeUpdates(listener) } catch (_: Exception) {}
            }, 20000)
        } catch (_: SecurityException) {
            toast("اجازه دسترسی به موقعیت گوشی داده نشده است")
        } catch (e: Exception) {
            toast("دریافت موقعیت ناموفق بود؛ مکان‌یابی گوشی را روشن کنید")
        }
    }

    private fun updateMyLocation(location: Location) {
        myLocation = GeoPoint(location.latitude, location.longitude)
        map.controller.setZoom(16.0)
        map.controller.animateTo(myLocation)
        refresh()
        toast("موقعیت شما روی نقشه نمایش داده شد")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationRequest) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) showMyLocation()
            else toast("برای نمایش موقعیت، اجازه مکان‌یابی را فعال کنید")
        }
    }

    private fun editDialog(old:Equipment?) {
        val fields=listOf("کد تجهیز","نام تجهیز","نوع تجهیز","نام شهر","نشانی","Latitude","Longitude","وضعیت تجهیز","تاریخ آخرین بازدید","توضیحات","اقدامات انجام‌شده")
        val current=old ?: Equipment("")
        val values=listOf(current.code,current.name,current.type,current.city,current.address,if(old==null) "" else current.lat.toString(),if(old==null) "" else current.lon.toString(),current.status,current.visitDate,current.notes,current.actions)
        val container=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        val inputs=fields.mapIndexed { i,label -> EditText(this).apply { hint=label; setSingleLine(i !in listOf(4,9,10)); setText(values[i]); if(i in listOf(4,9,10)) minLines=2; container.addView(this) } }
        val scroll=ScrollView(this).apply { addView(container) }
        val dlg=AlertDialog.Builder(this).setTitle(if(old==null)"افزودن تجهیز" else "ویرایش تجهیز").setView(scroll)
            .setPositiveButton("ذخیره",null).setNegativeButton("انصراف",null).create()
        dlg.setOnShowListener {
            dlg.getButton(-1).setOnClickListener {
                val code=inputs[0].text.toString().trim()
                val lat=inputs[5].text.toString().trim().replace(',','.').toDoubleOrNull()
                val lon=inputs[6].text.toString().trim().replace(',','.').toDoubleOrNull()
                if(code.isBlank() || lat==null || lon==null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { toast("کد تجهیز و مختصات معتبر الزامی است"); return@setOnClickListener }
                if(old!=null && old.code!=code) db.delete(old.code)
                db.upsert(Equipment(code,inputs[1].text.toString(),inputs[2].text.toString(),inputs[3].text.toString(),inputs[4].text.toString(),lat,lon,inputs[7].text.toString(),inputs[8].text.toString(),inputs[9].text.toString(),inputs[10].text.toString()),true)
                dlg.dismiss(); refresh(); refreshCities(findCitySpinner())
            }
        }
        dlg.show()
    }
    private fun findCitySpinner():Spinner {
        val root=findViewById<android.view.ViewGroup>(android.R.id.content)
        fun find(v:android.view.View):Spinner? { if(v is Spinner) return v; if(v is android.view.ViewGroup) for(i in 0 until v.childCount) find(v.getChildAt(i))?.let{return it}; return null }
        return find(root)!!
    }
    private fun openNeshan(e:Equipment) {
        val uri=Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}(${Uri.encode(e.name.ifBlank { e.code })})")
        val intent=Intent(Intent.ACTION_VIEW,uri)
        try { startActivity(intent) } catch(_:Exception) { toast("برنامه نقشه سازگار پیدا نشد") }
    }
    private fun pick(request:Int, types:Array<String>) {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type=types.first(); putExtra(Intent.EXTRA_MIME_TYPES,types) }
        startActivityForResult(i,request)
    }
    private fun pickCreate() {
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; putExtra(Intent.EXTRA_TITLE,"tajhizat-gps.xlsx") }
        startActivityForResult(i,exportXlsx)
    }
    @Deprecated("Legacy picker callback kept for broad Android compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null) return
        val uri=data.data!!
        try {
            if(requestCode==importXlsx) {
                AlertDialog.Builder(this).setTitle("روش ورود اطلاعات")
                    .setMessage("آیا رکوردهای دارای کد تکراری با اطلاعات فایل جایگزین شوند؟\nخیر: اطلاعات قبلی حفظ می‌شود.")
                    .setPositiveButton("جایگزینی") { _,_-> doImport(uri,true) }
                    .setNegativeButton("حفظ اطلاعات قبلی") { _,_-> doImport(uri,false) }.show()
            } else if(requestCode==exportXlsx) {
                contentResolver.openOutputStream(uri)?.use { Xlsx.export(it,db.all()) }
                toast("خروجی Excel ذخیره شد")
            }
        } catch(e:Exception) { toast("خطا: ${e.message ?: "نامشخص"}") }
    }
    private fun doImport(uri:Uri, replace:Boolean) {
        try {
            val (added,skipped)=Xlsx.import(this,uri,db,replace)
            refresh(); refreshCities(findCitySpinner())
            toast("ورود تمام شد؛ افزوده/به‌روزرسانی: $added، ردشده/تکراری: $skipped")
        } catch(e:Exception) { toast("خطا در ورود Excel: ${e.message ?: "فایل نامعتبر"}") }
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); if(::map.isInitialized) map.onResume() }
    override fun onPause() { if(::map.isInitialized) map.onPause(); super.onPause() }
}
