# -*- coding: utf-8 -*-

from django.conf import settings
from django.contrib import admin
from django.urls import path
from django.conf.urls.static import static

from core.api import api

urlpatterns = [
    path("admin/", admin.site.urls),
    path("api/", api.urls),
] + static("/recordings/", document_root=settings.MEDIA_ROOT)
