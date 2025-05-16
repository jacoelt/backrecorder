# -*- coding: utf-8 -*-

from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from ninja import Router, errors
from django.contrib.auth import authenticate, get_user_model, login, logout
from pydantic import validate_email
from pydantic_core import PydanticCustomError
from . import schemas

router = Router()


@router.post("/register", auth=None, response={201: schemas.UserSchema})
def register(request, payload: schemas.SignInSchema):
    try:
        validate_email(payload.email)
    except PydanticCustomError as e:
        raise errors.ValidationError(f"Invalid email")

    try:
        validate_password(payload.password)
    except ValidationError as e:
        raise errors.ValidationError(e.messages)

    if get_user_model().objects.filter(email=payload.email).exists():
        raise errors.ValidationError("Email already exists")

    get_user_model().objects.create_user(
        username=payload.email, email=payload.email, password=payload.password
    )
    user = authenticate(request, username=payload.email, password=payload.password)
    if user is None:
        return {"error": "User could not be authenticated after registration"}
    login(request, user)
    return 201, user


@router.post("/login", auth=None, response=schemas.UserSchema)
def login_view(request, payload: schemas.SignInSchema):
    user = authenticate(request, username=payload.email, password=payload.password)
    if user is not None:
        login(request, user)
        return user
    raise errors.AuthenticationError("Invalid credentials")


@router.post("/logout", response={204: None})
def logout_view(request):
    logout(request)
    return 204, None


@router.get("/me", response=schemas.UserSchema)
def me(request):
    return request.user
