package com.davv.trusti

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.davv.trusti.databinding.FragmentSettingsBinding
import com.davv.trusti.utils.ProfileManager

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Profile Settings
        binding.etUsername.setText(ProfileManager.getUsername(requireContext()))
        binding.tvDisambiguation.text = ProfileManager.getDisambiguation(requireContext())

        binding.btnRoll.setOnClickListener {
            binding.tvDisambiguation.text = ProfileManager.rollDisambiguation(requireContext())
        }

        binding.btnSaveProfile.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                ProfileManager.setUsername(requireContext(), username)
            }
        }

        // Theme toggle
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentMode = prefs.getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        binding.themeToggleGroup.check(
            when (currentMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.btnThemeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.btnThemeDark
                else -> R.id.btnThemeSystem
            }
        )
        binding.themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putInt(KEY_THEME, mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREFS_NAME = "trusti_prefs"
        const val KEY_THEME = "theme_mode"
    }
}
