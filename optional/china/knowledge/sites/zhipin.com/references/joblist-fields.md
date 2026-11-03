# 求人一覧のフィールド

中国向けBOSS直聘の上流資料に含まれる記録。/wapi/zpgeek/search/joblist.jsonのzpData.jobListには次のフィールドがある。現在の応答で有無と意味を確認し、未取得の値を補完しない。

| フィールド | 内容 |
|---|---|
| jobName | 職種名 |
| salaryDesc | 給与の原文表示。通貨・支給回数を含む場合がある |
| brandName | 企業名 |
| brandScaleName、brandStageName、brandIndustry | 規模、調達段階、業種 |
| cityName、areaDistrict、businessDistrict | 都市、地区、エリア |
| jobExperience、jobDegree | 経験と学歴 |
| skills、welfareList、jobLabels | スキル、福利厚生、ラベル |
| bossName、bossTitle、bossOnline | 採用担当者、肩書、オンライン状態 |
| encryptJobId、securityId、lid | サービスの識別値 |
| itemId | 一覧内の番号 |

フィールド名、識別値、給与の原文を翻訳して書き換えない。説明と結論は日本語で行う。
