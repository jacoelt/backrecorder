# -*- coding: utf-8 -*-


from ninja import Router

from core.api.recording.schemas import RecordingSchema


router = Router()


@router.get("/get_list", auth=None, response=list[RecordingSchema])
def get_list(request):
    """
    This endpoint returns a list of recordings for current user.
    """
    return request.user.recordings.all()
