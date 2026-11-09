# GawharaAnti-outlaw

Plugin حماية وإشراف لسيرفر Minecraft Java المبني على Paper.

## المميزات الحالية

- فلتر شتائم قابل للتعديل (قائمة كلمات من `config.yml`).
- حظر الروابط غير المسموح بها، مع السماح بامتدادات الصور والصوت والفيديو المحددة، وروابط منصات الفيديو المعروفة.
- تنبيه Discord عند المخالفات.
- مراقبة إحصائية لأنماط كسر الخامات وإرسال تنبيه للمراجعة.
- قراءة اسم Client Brand المعلن من اللاعب وإرسال تنبيه عند تطابق كلمات مشبوهة.
- أوامر reload/status/exempt.

## حدود مهمة

- لا يستطيع Plugin عادي فحص ملفات جهاز اللاعب أو معرفة كل المودات المثبتة. اسم الـ client brand يرسله العميل ويمكن إخفاؤه أو تزويره؛ لذلك التنبيه ليس إثبات غش ولا يسبب حظراً تلقائياً.
- مراقبة التعدين إحصائية وليست إثباتاً لـ X-Ray. قد تنتج إنذارات كاذبة، لذا لا تستخدمها وحدها للعقوبة.
- نظام Anti-Xray الحقيقي في Paper يتم ضبطه من إعدادات Paper للعالم (`paper-world-defaults.yml` أو إعداد العالم المناسب). فعّل `anti-xray` في إعدادات Paper وراجع وثائق إصدار Paper لديك.
- الروابط التي لا تنتهي بامتداد ملف أو لا تنتمي لمنصة فيديو مدرجة سيتم حظرها. أضف نطاقات موثوقة إلى `allowed-video-hosts` عند الحاجة.
- رقم Discord Channel ID ليس رابط Webhook. أنشئ Webhook للروم وضع عنوانه في `discord.webhook-url`.

## البناء من GitHub

1. أنشئ Repository جديداً باسم `GawharaAnti-outlaw`.
2. ارفع محتويات هذا المشروع إلى جذر الـ Repository، بحيث يكون مجلد `.github` ظاهراً.
3. افتح تبويب **Actions** وانتظر انتهاء `Build GawharaAnti-outlaw`.
4. افتح عملية البناء الناجحة، ثم نزّل Artifact باسم `GawharaAnti-outlaw`.
5. فك ضغط Artifact وخذ ملف `.jar` وضعه في مجلد `plugins` ثم أعد تشغيل السيرفر.

يمكن أيضاً البناء محلياً باستخدام JDK 25 وMaven (المشروع يستهدف أحدث خط Paper 26.3 المتاح):
```bash
mvn clean package
```
ملف JAR الناتج سيكون داخل `target/`.

## إعداد Discord

1. من إعدادات الروم المطلوب افتح Integrations ثم Webhooks.
2. أنشئ Webhook وانسخ عنوانه.
3. بعد تشغيل السيرفر مرة واحدة، افتح:
   `plugins/GawharaAnti-outlaw/config.yml`
4. ضع الرابط في `discord.webhook-url`:
```yaml
discord:
  enabled: true
  webhook-url: "YOUR_DISCORD_WEBHOOK_URL"
  channel-id: "1569226221886382230"
```
5. أعد تشغيل السيرفر أو نفّذ `/gao reload`.

**أمان:** لا ترفع رابط Webhook إلى GitHub، ولا ترسله في محادثة عامة. إذا ظهر في مكان عام، احذفه وأنشئ Webhook جديداً.

## الأوامر والصلاحيات

- `/gao status`
- `/gao reload`
- `/gao exempt add <player>`
- `/gao exempt remove <player>`

الصلاحية الإدارية: `gawharaoutlaw.admin` (افتراضياً OP).  
تجاوز الفلاتر والمراقبة: `gawharaoutlaw.bypass` (افتراضياً لا أحد).

ملاحظة: قائمة exemptions الحالية مؤقتة وتُصفّر عند إعادة تشغيل السيرفر؛ استخدم permission `gawharaoutlaw.bypass` للاستثناء الدائم.
