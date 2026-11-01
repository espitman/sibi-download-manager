# طرح پنل وب SDM — نسخهٔ ۱

طرح تصویری تأییدشده برای DMF-050. پیاده‌سازی اصلی پنل روی گوشی نصب شده؛ نتیجهٔ تطبیق ۲۱ حالت، تست‌ها و محدودهٔ دقیق آزمون‌های نهایی در ../STATUS.md و ../verification/ ثبت شده‌اند. تأیید تصاویر به‌تنهایی مدرک تست نیست.

[گالری تصاویر](index.html) · [پرامپت‌های اصلی و اصلاحی](prompts.json)

تصاویر با ابزار داخلی تصویرسازی تولید شده‌اند. رابط انگلیسی با زبان فعلی SDM هم‌راستاست. هویت بصری از مرجع Open Design موجود گرفته شده است: پس‌زمینهٔ تیره، سطح زغالی، حاشیهٔ ظریف، طلایی برای عملیات اصلی و سبز برای وضعیت فعال. تصاویر ایدهٔ بصری هستند؛ اعداد و نام‌ها دادهٔ آزمایشی‌اند. هندسه، لوگو و آیکون‌های مشترک در نمونهٔ اجرایی باید از عناصر اصلی SDM استفاده کنند.

## پوشش صفحات و حالت‌ها

| مجموعه | صفحه‌ها و حالت‌ها | تصویر |
| --- | --- | --- |
| ۱ | 01 اتصال و تأیید روی گوشی؛ 02 دانلودها و انتخاب گروهی | [تصویر](01-connect-downloads.png) |
| ۲ | 03 صف و تغییر ترتیب؛ 04 جزئیات HTTP، نمودار، اتصال‌ها، هدرها و بخش‌ها | [تصویر](02-queue-http-details.png) |
| ۳ | 05 لینک تکی/چندخطی و اعتبارسنجی؛ 06 مگنت/فایل تورنت و دریافت متادیتا؛ 07 انتخاب محتوا | [تصویر](03-add-links-torrent.png) |
| ۴ | 08 جزئیات تورنت و فایل‌ها/همتاها/اشتراک‌گذاری؛ 09 فایل‌های گوشی، جابه‌جایی، پیش‌نمایش و حذف | [تصویر](04-torrent-files.png) |
| ۵ | 10 تنظیمات دانلود و ذخیره‌سازی؛ 11 سرور محلی، پورت، تأیید مرورگر و لغو مجوز | [تصویر](05-settings-lan.png) |
| ۶ | 12 زمان‌بندی؛ 13 محدودیت سرعت؛ 14 لینک منقضی؛ 15 تغییر نام، checksum، حذف رکورد و بازیابی بکاپ | [تصویر](06-operation-dialogs.png) |
| ۷ | 16 قطع اتصال؛ 17 لغو مجوز؛ 18 فهرست خالی/خطا؛ 19 دانلودها در موبایل؛ 20 جزئیات در موبایل؛ 21 فایل‌ها در موبایل | [تصویر](07-states-mobile.png) |

## رفتارهای قطعی

- دانلود HTTP و موتور تورنت، صف و فایل‌های خروجی همگی روی گوشی هستند. بستن تب، قطع اتصال پنل یا خاموش‌کردن سرور پنل، دانلود را متوقف نمی‌کند.
- همهٔ مرورگرها باید با تأیید گوشی یا کد جفت‌سازی کوتاه‌عمر احراز شوند. پنل عمومی بدون احراز هویت و Basic Auth وجود ندارد. کد تأیید فقط هنگام جفت‌سازی نمایش داده می‌شود؛ اطلاعات ارتباط معتبر همیشه وضعیت گوشی را نشان می‌دهد.
- شروع/توقف سرور و اجازهٔ دسترسی به پوشه‌ها تحت کنترل گوشی است. درخواست‌های مجوز سیستم Android در گوشی تأیید می‌شوند. فرمانی که سرور را متوقف یا مجوز مرورگر فعلی را لغو کند، پیام تأیید و توضیح قطع اتصال دارد.
- دادهٔ پنل از همان مخزن و صف اپ می‌آید؛ شمارندهٔ HTTP تعداد جریان‌های فعال واقعی است و جزئیات تورنت همتا و seed نشان می‌دهد. سرعت زیر ۱ MB/s با KB/s نمایش داده می‌شود؛ نمودار تاریخچهٔ واقعی ۶۰ ثانیه را می‌خواند.
- قطع اتصال: آخرین همگام‌سازی و اطلاعات قبلی نشان داده می‌شود، فرمان‌های تغییردهنده غیرفعال‌اند؛ پس از اتصال دوباره وضعیت گوشی دریافت می‌شود. فرمان‌های قدیمی خودکار تکرار نمی‌شوند.
- حذف رکورد دانلود با نگه‌داشتن فایل، از حذف دائمی فایل روی گوشی جداست. گزینهٔ پیش‌فرض نگه‌داشتن فایل است. جابه‌جایی و تغییر نام باید رفتار ایمن موجود اپ را حفظ کنند.
- «Download to computer» انتقال اختیاری فایل موجود از گوشی به مرورگر است؛ مقصد وظایف دانلود همچنان گوشی است. پیش‌نمایش فقط برای نوع فایل‌های قابل پشتیبانی مرورگر نمایش داده می‌شود.
- جابه‌جایی صف هم تکی و هم گروهی است. انتخاب همه، توقف/ادامه/لغو، اولویت و پاک‌کردن رکوردها از منطق موجود اپ استفاده می‌کنند.
- انتخاب فایل‌های تورنت پیش از افزودن نهایی انجام می‌شود. وضعیت چک‌باکس‌ها در جزئیات دانلود فعال صرفاً محتوای انتخاب‌شده را نشان می‌دهد؛ تغییر زندهٔ انتخاب فقط در صورت پشتیبانی امن موتور عرضه می‌شود.
- تلاش مجدد خودکار فقط برای خطای موقت است؛ توقف دستی و خطای دائمی هرگز خودکار تکرار نمی‌شوند. تأخیر مطابق درخواست قبلی: زیر ۱۰ ثانیه گام ۱ ثانیه، سپس گام ۱۰ ثانیه، حداکثر ۳ دقیقه. ورودی زمان بیش از این مقدار را نمی‌پذیرد.
- تاز‌ه‌سازی لینک برای حفظ پیشرفت به تطابق هویت معتبر فایل و شرایط Range نیاز دارد؛ نام و اندازه به‌تنهایی کافی نیستند. شروع از صفر فقط پس از تأیید صریح انجام می‌شود.
- در بورد دیالوگ‌ها، حالت موفق و خطا و دیالوگ‌های مختلف برای مرور طراحی کنار هم آمده‌اند؛ در جریان واقعی هم‌زمان باز یا نمایش داده نمی‌شوند. بکاپ در Settings قرار دارد؛ ارتباط تصویری آن با عملیات فایل، محل ناوبری واقعی را تغییر نمی‌دهد.
- مطابق آخرین درخواست کاربر، کنترل بروزر داخلی گوشی از محدودهٔ پنل وب حذف شده است؛ بروزر داخل اپ باقی می‌ماند.
- کنترل‌های حذف‌شدهٔ Always keep active، تشخیص/دانلود خودکار رسانه، دسترسی ویژهٔ YouTube و Proxy بازنمی‌گردند.
- در عرض دسکتاپ جدول و نوار کناری، و در عرض موبایل کارت‌ها و ناوبری پایین استفاده می‌شود. افزودن دانلود، دیالوگ‌ها و تنظیمات در عرض کم به پنل تمام‌عرض تبدیل می‌شوند.

## مرجع

Open Design: `/Users/espitman/Library/Application Support/Open Design/namespaces/release-stable/data/projects/13ab3c0f-1414-409f-934f-981d4a24c89f/index.html`.

این بسته طراحی است؛ هیچ تیک تکمیل پیاده‌سازی، تست دستگاه یا آماده‌بودن برای انتشار به آن اضافه نشده است.

## Browser approval persistence

The user requires first-use approval only. Approved browsers remain authorized until revoked on the phone; closing a tab, restarting the app, stopping/restarting the panel, and changing its port must not require another code. The phone stores browser credentials encrypted with Android Keystore in its no-backup directory. The panel uses a scoped HttpOnly/SameSite cookie for native file transfers and cross-port reconnection, with a browser credential retained locally for reopening the panel. Pairing codes and unapproved requests still expire after two minutes. Clearing browser/site data or reinstalling the phone app requires pairing again.

The user requested larger text and controls after reviewing the live panel. This larger reading scale supersedes the illustrative small type in the visual boards. Icons must remain visible when switching tabs and during live updates.

## Complete app/web parity

The user excludes the in-app Browser from web-panel scope. The computer browser handles browsing; the panel does not remotely control phone Browser navigation, tabs, history, private sessions, preferences or sign-in. The native app Browser remains available. All other supported app operations remain in scope. Android document permissions, native open/share targets and required system approvals are initiated from the web and completed on the phone, with the result reflected back in the panel. Removed product features remain removed.

A capability is complete only when its web control, shared implementation and functional evidence exist. A design state is complete only when its actual desktop/mobile render is compared with the corresponding approved reference, incorporating the user's explicit size and Pause/Resume corrections.
