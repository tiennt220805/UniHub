import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert } from 'lucide-react'
import { useForm } from 'react-hook-form'

import type { AppError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
import { Field, FieldError, Label } from '@/shared/ui/field'
import { Input } from '@/shared/ui/input'
import { useSignIn } from '../api/mutations'
import { signInSchema, toLoginRequest, type SignInForm as SignInValues } from '../model/sign-in.schema'

// UX by status (web-coding-standards.md §6). A 401 is "bad credentials" here, never an expired
// session: the sign-in request carries no token. A SUSPENDED account answers the same 401 by design
// (paths/user.yaml#/login), so the message must not say why.
function errorMessage(error: AppError): string {
  switch (error.status) {
    case 400:
      return 'Email không hợp lệ.'
    case 401:
      return 'Email hoặc mật khẩu không đúng.'
    case 429:
      return error.retryAfterSeconds === null
        ? 'Bạn đã thử đăng nhập quá nhiều lần. Vui lòng thử lại sau.'
        : `Bạn đã thử đăng nhập quá nhiều lần. Vui lòng thử lại sau ${error.retryAfterSeconds} giây.`
    default:
      return 'Không đăng nhập được. Vui lòng thử lại.'
  }
}

export function SignInForm({ onSignedIn }: { onSignedIn?: () => void }) {
  const signIn = useSignIn({ onSuccess: onSignedIn })
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SignInValues>({ resolver: zodResolver(signInSchema) })

  return (
    <form
      noValidate
      onSubmit={handleSubmit((values) => signIn.mutate(toLoginRequest(values)))}
      className="grid gap-4"
    >
      <Field>
        <Label htmlFor="email">Email</Label>
        <Input
          id="email"
          type="email"
          autoComplete="username"
          aria-invalid={errors.email ? true : undefined}
          aria-describedby={errors.email ? 'email-error' : undefined}
          className="h-12"
          {...register('email')}
        />
        {errors.email && (
          <FieldError id="email-error" role="alert">
            {errors.email.message}
          </FieldError>
        )}
      </Field>
      <Field>
        <Label htmlFor="password">Mật khẩu</Label>
        <Input
          id="password"
          type="password"
          autoComplete="current-password"
          aria-invalid={errors.password ? true : undefined}
          aria-describedby={errors.password ? 'password-error' : undefined}
          className="h-12"
          {...register('password')}
        />
        {errors.password && (
          <FieldError id="password-error" role="alert">
            {errors.password.message}
          </FieldError>
        )}
      </Field>
      {signIn.isError && (
        <div role="alert" className="flex items-start gap-2.5 rounded-lg bg-destructive-soft p-3 text-sm text-destructive">
          <CircleAlert aria-hidden="true" className="mt-0.5 size-4.5 shrink-0" />
          <p>{errorMessage(signIn.error)}</p>
        </div>
      )}
      <Button type="submit" size="lg" className="w-full" disabled={signIn.isPending}>
        {signIn.isPending ? 'Đang đăng nhập…' : 'Đăng nhập'}
      </Button>
    </form>
  )
}
