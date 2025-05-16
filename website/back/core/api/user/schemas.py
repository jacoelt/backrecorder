import uuid
from ninja import Schema
from pydantic import BaseModel


class SignInSchema(BaseModel):
    email: str
    password: str


class UserSchema(Schema):
    id: uuid.UUID
    email: str

    class Config:
        from_attributes = True
