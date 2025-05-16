import uuid
from django.db import models

from core.models.helpers import SoftDeleteModel
from core.models.user import CustomUser


def update_filename(instance, filename):
    return f"{instance.user_id}/{instance.id}"


class Recording(SoftDeleteModel):
    id = models.UUIDField(primary_key=True, default=uuid.uuid4, editable=False)
    name = models.CharField(max_length=255, blank=True)
    user = models.ForeignKey(
        CustomUser, on_delete=models.CASCADE, related_name="recordings"
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    duration = models.DurationField()
    start_time = models.DateTimeField(null=True, blank=True)
    file = models.FileField(upload_to=update_filename)

    def save(self, *args, **kwargs):
        super().save(*args, **kwargs)
        if self.start_time is None:
            self.start_time = self.created_at - self.duration

    def __str__(self):
        return f"Recording {self.id} by {self.user.username}"
