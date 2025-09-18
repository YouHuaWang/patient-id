package com.example.patientid.core

data class PatientInfo(
    val name: String,
    val birthDate: String,
    val medicalId: String,
    val examType: String = ""
)

data class CombinedOCRResult(
    val fullText: String,
    val fieldsLine: Map<String, String>,
    val fieldsBlock: Map<String, String>
)
