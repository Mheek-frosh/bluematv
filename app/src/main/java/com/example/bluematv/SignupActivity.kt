package com.example.bluematv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.bluematv.databinding.ActivitySignupBinding

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignup.setOnClickListener {
            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (name.isEmpty()) {
                binding.etName.error = "Name is required"
                return@setOnClickListener
            }
            if (email.isEmpty()) {
                binding.etEmail.error = "Email is required"
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                binding.etPassword.error = "Password is required"
                return@setOnClickListener
            }

            // Navigate to main dashboard
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.tvGoToLogin.setOnClickListener {
            finish()  // Go back to LoginActivity
        }
    }
}
