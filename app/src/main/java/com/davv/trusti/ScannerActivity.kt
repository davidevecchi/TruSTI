package com.davv.trusti

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.davv.trusti.connection.QrHelper
import com.davv.trusti.databinding.ActivityScannerBinding
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) binding.barcodeScanner.resume()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener { finish() }

        binding.barcodeScanner.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val pk = QrHelper.parse(result.text) ?: return
                binding.barcodeScanner.pause()
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PUBLIC_KEY, pk))
                finish()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.barcodeScanner.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScanner.pause()
    }

    companion object {
        const val EXTRA_PUBLIC_KEY = "public_key"
    }
}
