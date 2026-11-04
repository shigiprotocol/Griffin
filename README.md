# Griffin

<p align="center"><img src="V2rayNG/app/src/main/res/drawable-nodpi/ic_griffin_logo.png" width="160" alt="Griffin"></p>

An Xray client for Android, built on PattNG / v2rayNG.

**تغییرات Griffin:**

* نام و آیکون جدید Griffin (لانچر، نوتیفیکیشن، لاک‌اسکرین و کاشی کنترل سنتر)
* تم سورمه‌ای و نارنجی، و پس‌زمینه‌ی مشکیِ کامل (OLED) در حالت تاریک
* حذف کامل هسته‌ی Aether
* بعد از اتصال، خودکار پینگ گرفته می‌شود و پرچم کشور و آی‌پی نهایی کانفیگ در صفحه‌ی اصلی، نوتیفیکیشن و کاشی کنترل سنتر نشان داده می‌شود
* اگر سرویس آی‌پی تنظیمات جواب ندهد، آی‌پی و کشور از Cloudflare گرفته می‌شود

**از PattNG:**

* cipherSuites و فینگرپرینت unsafe در تنظیمات و شیرلینک
* امکان اتصال به کانفیگ‌های رمزنگاری‌نشده برای آدرس‌های عمومی در VLESS و TROJAN (Xray-core)

## ساخت

هر push روی شاخه‌ی `my-releases` در GitHub Actions، APK را می‌سازد (تب Actions → آخرین اجرا → Artifacts).
برای امضای دائمی با کلید خودتان، secretهای `APP_KEYSTORE_BASE64`، `APP_KEYSTORE_PASSWORD`، `APP_KEYSTORE_ALIAS` و `APP_KEY_PASSWORD` را اضافه کنید؛ بدون آن‌ها با یک کلید ثابت از کش Actions امضا می‌شود.

## Credits

[PattNG](https://github.com/patterniha/PattNG) by @patterniha · [v2rayNG](https://github.com/2dust/v2rayNG) by 2dust · [Xray-core](https://github.com/XTLS/Xray-core)
