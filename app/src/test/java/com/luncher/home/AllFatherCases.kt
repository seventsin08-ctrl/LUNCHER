package com.luncher.home

import com.luncher.home.AllFatherBrain.Cat.*

/**
 * Simulated notifications All Father must understand: (app, who, text) → category, important?,
 * the actions he should take, and (optionally) the task title.
 */
object AllFatherCases {
    class Case(val app: String, val who: String, val text: String, val cat: AllFatherBrain.Cat, val imp: Boolean, val acts: Set<String> = emptySet(), val task: String? = null)
    private fun c(app: String, who: String, text: String, cat: AllFatherBrain.Cat, imp: Boolean, vararg acts: String, task: String? = null) = Case(app, who, text, cat, imp, acts.toSet(), task)

    val ALL = listOf(
        // ---- announcements
        c("Messenger", "BSIT 1-C UPDATES", "ANNOUNCEMENT: No classes on Monday. Research draft due Friday 5 PM.", ANNOUNCEMENT, true, "PIN", "TASK", task = "Research draft"),
        c("Messenger", "PROPSMEN- CHEER DANCE", "@everyone practice moved to 4 PM tomorrow, gym.", ANNOUNCEMENT, true, "PIN", "REMINDER"),
        c("Messenger", "BSIT 1-C (2026-2027)", "Attention all: walang pasok bukas due to the typhoon", ANNOUNCEMENT, true, "PIN"),
        c("Messenger", "KNM Student Council", "Advisory: Enrollment for 2nd sem starts Dec 1.", ANNOUNCEMENT, true, "PIN"),
        c("Gmail", "Registrar · Kolehiyo", "Official notice: classes are suspended tomorrow.", ANNOUNCEMENT, true, "PIN"),
        c("Messenger", "NSTP Announcements", "Please submit your reflection paper on or before Nov 30.", ANNOUNCEMENT, true, "PIN", "TASK"),
        c("Telegram", "DevMart Admin", "Important update: new rates start next week.", ANNOUNCEMENT, true, "PIN"),
        // ---- clients & leads
        c("Messenger", "Grain Café (client)", "Hi John, can you send the revised menu by Friday? Thanks!", CLIENT, true, "TASK", task = "Send the revised menu"),
        c("Messenger", "Park Café", "Interested in a website for our café. How much for a simple one?", CLIENT, true, "LEAD", "TASK"),
        c("Messenger", "Amang's Chicken House", "Magkano po ang menu design?", CLIENT, true, "LEAD", "TASK"),
        c("WhatsApp", "Green Café", "We already sent the down payment ₱3,000 via GCash", CLIENT, true, "MONEY_IN"),
        c("Messenger", "Grain Café (client)", "ok thanks!", CLIENT, false, "FILE"),
        c("Messenger", "Maria (lead)", "Do you also make apps? We need a quotation", CLIENT, true, "LEAD", "TASK"),
        c("Gmail", "orders@smartmenu.ph", "Invoice #1023 for November is due Dec 5", EMAIL, true, "TASK"),
        c("Messenger", "Coffee Lab", "Can you deliver the logo revision tomorrow?", CLIENT, true, "TASK", task = "Deliver the logo revision"),
        // ---- school
        c("Messenger", "Prof. Santos", "Please submit your C++ activity before 11:59 PM tonight.", SCHOOL, true, "TASK", task = "Submit your C++ activity"),
        c("Messenger", "Jia rxse", "may quiz daw tayo sa NSTP bukas", SCHOOL, true, "TASK"),
        c("Gmail", "Registrar · Kolehiyo", "Your enrollment slip is ready. Please claim it before Nov 28.", EMAIL, true, "TASK"),
        c("Gmail", "Google Classroom", "New assignment: Truth tables worksheet (due Monday)", EMAIL, true, "TASK"),
        c("Messenger", "Carlo Estrella", "Pre, asan ka na? start na yung defense", SCHOOL, true),
        c("Messenger", "BSIT 1-C (2026-2027): Rj Prince Tagaro", "ok intindihin sabi mo ehh", MESSAGE, false),
        // ---- personal messages
        c("Messenger", "Jia rxse", "haha sige see you later 😂", MESSAGE, false, "FILE"),
        c("Messenger", "Mama", "Anak, tawagan mo ako pag may oras ka", MESSAGE, true, "REPLY"),
        c("Messages", "Mama", "Asan ka na? Umuwi ka na", MESSAGE, true, "REPLY"),
        c("Messenger", "Jia rxse", "sent a sticker", MESSAGE, false),
        c("Messenger", "John Cedrick Culia", "🤣🤣", MESSAGE, false),
        c("Messenger", "Ranier", "Can you help me with my code? urgent", MESSAGE, true, "REPLY"),
        c("WhatsApp", "Tita Liza", "Happy birthday in advance!", MESSAGE, false),
        c("Telegram", "Kuya", "Call me when you see this, important", MESSAGE, true, "REPLY"),
        c("Messenger", "Jia rxse", "nice", MESSAGE, false),
        // ---- email
        c("Gmail", "Shopee", "11.11 is back! Up to 90% off — today only.", PROMO, false, "MUTE"),
        c("Gmail", "Medium Daily Digest", "Stories for you: 10 habits of disciplined people", PROMO, false, "MUTE"),
        c("Gmail", "LinkedIn", "You appeared in 5 searches this week", EMAIL, false, "FILE"),
        c("Gmail", "Jobstreet", "Interview invitation: Junior Web Developer, Tuesday 10 AM", EMAIL, true),
        c("Gmail", "Netlify", "Your site devmart-ph deployed successfully", EMAIL, false),
        c("Gmail", "Canva", "Your free trial ends in 3 days — don't miss out", PROMO, false, "MUTE"),
        c("Gmail", "GitHub", "Please verify your email address", EMAIL, true),
        // ---- money
        c("GCash", "GCash", "You received ₱3,000.00 from M. Reyes. Ref 7712.", MONEY, true, "MONEY_IN"),
        c("GCash", "GCash", "You have sent ₱250.00 to Jollibee.", MONEY, false, "MONEY_OUT"),
        c("BPI", "BPI", "Your credit card bill of ₱4,500 is due on Dec 3.", MONEY, true, "TASK"),
        c("Maya", "Maya", "Get 5% cashback on your next load!", MONEY, false),
        c("GCash", "GCash", "Payment of ₱1,200.00 to Meralco was successful.", MONEY, false, "MONEY_OUT"),
        // ---- security
        c("Messages", "GCash", "Your OTP is 482913. Do not share this code.", SECURITY, true, "FLAG"),
        c("Gmail", "Google", "New sign-in to your account from Windows", SECURITY, true, "FLAG"),
        c("Messages", "BDO", "Your verification code is 7731", SECURITY, true, "FLAG"),
        // ---- social
        c("Instagram", "carlo.estrella", "sent you a reel", SOCIAL, false, "FILE"),
        c("Instagram", "jia.rxse", "liked your story", SOCIAL, false),
        c("Facebook", "Facebook", "Carlo mentioned you in a comment", SOCIAL, true, "REPLY"),
        c("TikTok", "TikTok", "Your video has 1,200 new views", SOCIAL, false),
        c("YouTube", "YouTube", "New video from Fireship", SOCIAL, false),
        // ---- shops
        c("Shopee", "Shopee", "Flash sale starts now! Vouchers up to ₱500", PROMO, false, "MUTE"),
        c("Shopee", "Shopee", "Your parcel is out for delivery", OTHER, true),
        c("Lazada", "Lazada", "Your order #8812 has been delivered", OTHER, true),
        c("Grab", "Grab", "Your rider is arriving", OTHER, true),
        c("foodpanda", "foodpanda", "Hungry? 50% off your next order", PROMO, false, "MUTE"),
        // ---- calendar & system
        c("Calendar", "Calendar", "NSTP quiz · Monday 9:00 AM", CALENDAR, true, "SCHEDULE"),
        c("Clock", "Alarm", "Wake up · 6:00 AM", CALENDAR, true, "SCHEDULE"),
        c("System", "Battery", "Battery at 18%. Charge soon.", SYSTEM, true, "FLAG"),
        c("Android System", "Storage", "Storage almost full", SYSTEM, true, "FLAG"),
        c("System", "Battery", "Battery at 64%", SYSTEM, false, "FILE"),
        c("Android System", "USB", "Charging this device via USB", SYSTEM, false, "FILE"),
        c("i Manager", "i Manager", "Phone is running smoothly", SYSTEM, false),
        // ===== round 3: fresh notifications
        c("Messenger", "BSIT 1-C UPDATES", "Reminder to all: bring your lab gowns tomorrow", ANNOUNCEMENT, true, "PIN"),
        c("Messenger", "Ma'am Cruz", "Class, the long test is moved to Thursday 1 PM.", SCHOOL, true, "REMINDER"),
        c("Messenger", "Ma'am Cruz", "Pakipasa ang assignment bago mag Friday", SCHOOL, true, "TASK"),
        c("Messenger", "Ella (groupmate)", "John can you finish the slides by tonight?", SCHOOL, true, "TASK"),
        c("Messenger", "Ella (groupmate)", "thank youu", MESSAGE, false),
        c("Messenger", "Park Café", "Sige po, we'll pay the balance on Monday", CLIENT, true, "TASK"),
        c("Messenger", "Brew & Co. (lead)", "Hello! What are your rates for a landing page?", CLIENT, true, "LEAD", "TASK"),
        c("Messenger", "Grain Café (client)", "Sent the GCash payment po ₱4,000", CLIENT, true, "MONEY_IN"),
        c("Messenger", "Grain Café (client)", "Ang ganda!! 😍", CLIENT, false),
        c("Messenger", "Lola", "Kumain ka na ba?", MESSAGE, true, "REPLY"),
        c("Messenger", "Jia rxse", "gising ka pa?", MESSAGE, true, "REPLY"),
        c("Messenger", "Jia rxse", "oo", MESSAGE, false),
        c("Messages", "+63 917 555 0101", "Congratulations! You won ₱50,000. Claim your prize at bit.ly/xx", PROMO, false, "MUTE"),
        c("Messages", "SMART", "Get 10GB for ₱99 — promo until Sunday!", PROMO, false, "MUTE"),
        c("Messages", "Globe", "Your load balance is low", OTHER, false),
        c("Gmail", "Prof. Santos", "Grades for the midterm are now posted", EMAIL, true),
        c("Gmail", "Upwork", "New job: Android app developer (₱15,000 budget)", EMAIL, true),
        c("Gmail", "Spotify", "Your Wrapped is here 🎁", EMAIL, false),
        c("Gmail", "Shopee", "Your order has shipped", EMAIL, false),
        c("Gmail", "noreply@netlify.com", "Deploy failed for devmart-ph", EMAIL, false),
        c("GCash", "GCash", "You received ₱500.00 from J. Mujal", MONEY, true, "MONEY_IN"),
        c("Maya", "Maya", "Your bill payment is overdue", MONEY, true, "TASK"),
        c("BDO", "BDO", "Purchase of ₱2,150.00 at SM Supermarket", MONEY, false, "MONEY_OUT"),
        c("Instagram", "grain.cafe", "sent you a message", SOCIAL, true, "REPLY"),
        c("Instagram", "jia.rxse", "replied to your story", SOCIAL, true, "REPLY"),
        c("TikTok", "TikTok", "carlo.estrella posted a video", SOCIAL, false),
        c("Facebook", "Facebook", "You have 3 new friend requests", SOCIAL, false),
        c("Calendar", "Google Calendar", "DevMart call with Park Café · 3:00 PM", CALENDAR, true, "SCHEDULE"),
        c("System", "Battery", "Battery at 9%. Connect charger.", SYSTEM, true, "FLAG"),
        c("Android System", "Security", "Security update available", SYSTEM, true, "FLAG"),
        c("Gmail", "Microsoft", "Your security code is 554210", SECURITY, true, "FLAG")
    )

    /** Deadline phrases → (has a time?, words kept). */
    val DUES = listOf(
        "due Friday 5 PM" to "friday 5 pm", "by Friday" to "friday", "before Nov 28" to "nov 28", "on or before Nov 30" to "nov 30",
        "due tomorrow" to "tomorrow", "ipasa bukas" to "bukas", "deadline is 12/5" to "12/5", "before 11:59 PM tonight" to "11:59 pm",
        "due on Monday" to "monday", "moved to 4 PM tomorrow" to "4 pm tomorrow"
    )
}
