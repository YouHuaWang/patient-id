package com.example.patientid.utils

object MedDict {

    // 單字翻譯 (逐詞用)
    val dict = mapOf(
        // ====== 方向 / 位置 ======
        "Rt" to "右",
        "R" to "右",
        "Lt" to "左",
        "L" to "左",
        "Bil" to "雙側",
        "Bilateral" to "雙側",

        "AP" to "前後位",
        "PA" to "後前位",
        "Lat" to "側位",
        "Oblique" to "斜位",
        "Axial" to "軸位",
        "Coronal" to "冠狀位",
        "Sagittal" to "矢狀位",
        "Supine" to "仰臥位",
        "Prone" to "俯臥位",
        "Flex" to "屈曲",
        "Extension" to "伸展",

        // ====== 常見部位 ======
        "Knee" to "膝",
        "Femur" to "股骨",
        "Tibia" to "脛骨",
        "Fibula" to "腓骨",
        "Humerus" to "肱骨",
        "Radius" to "橈骨",
        "Ulna" to "尺骨",
        "Spine" to "脊椎",
        "Cervical" to "頸椎",
        "Lumbar" to "腰椎",
        "Chest" to "胸部",
        "Abdomen" to "腹部",
        "Pelvis" to "骨盆",
        "Skull" to "顱骨"
    )

    // 常見整句 (phraseDict)
    val phraseDict = mapOf(
        "Rt Knee AP+Lat" to "右膝 前後位+側位",
        "Lt Knee AP+Lat" to "左膝 前後位+側位",
        "Lower Limbs - Rt AP" to "右下肢 前後位",
        "Lower Limbs - Lt AP" to "左下肢 前後位",
        "Lower extremities" to "下肢",
        "Upper extremities" to "上肢"
    )

    // 檢查代碼 (最精準)
    val codeDict = mapOf(
        "*340-0020" to "右膝 前後位+側位",
        "*340-0010" to "左膝 前後位+側位",
        "32017C" to "下肢 X 光",
        "32018C" to "下肢連續攝影"
    )
}