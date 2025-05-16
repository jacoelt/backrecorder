from ninja import ModelSchema

from core.models.recording import Recording


class RecordingSchema(ModelSchema):
    class Config:
        model = Recording
        model_fields = [
            "id",
            "name",
            "file",
            "start_time",
            "duration",
            "created_at",
            "updated_at",
        ]
