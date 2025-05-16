from django.middleware.csrf import get_token
from ninja import NinjaAPI
from ninja.security import django_auth
from .user import router as user_router
from .recording import router as recording_router


api = NinjaAPI(csrf=True, auth=django_auth)


api.add_router("/user/", user_router)
api.add_router("/recording/", recording_router)


# @api.get("/csrf", auth=None)
# def get_csrf_token(request):
#     return {"csrftoken": get_token(request)}
