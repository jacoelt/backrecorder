from django.contrib import admin
from django.contrib.auth.admin import UserAdmin

from core.models.recording import Recording
from core.models.user import CustomUser


class RecordingAdmin(admin.ModelAdmin):
    list_display = ("id", "name", "user__username", "start_time")
    list_filter = ("user",)
    search_fields = ("user__username", "file")
    exclude = ("deleted_at",)


admin.site.register(CustomUser, UserAdmin)
admin.site.register(Recording, RecordingAdmin)
