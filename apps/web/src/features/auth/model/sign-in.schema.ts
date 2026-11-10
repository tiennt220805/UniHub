import { z } from '@/shared/config/zod'

import type { LoginRequest } from '../api/requests'

// Mirrors LoginRequest (schemas/user.yaml): an email and a non-empty password. The password gets no
// length rule on purpose; whether it is right is the server's answer, and a rule here would only
// tell an attacker less than the 401 does.
export const signInSchema = z.object({
  email: z.email('Email không hợp lệ.'),
  password: z.string().min(1, 'Vui lòng nhập mật khẩu.'),
})

export type SignInForm = z.infer<typeof signInSchema>

export const toLoginRequest = (form: SignInForm): LoginRequest => ({
  email: form.email,
  password: form.password,
})
