import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'

import type { AppError } from '@/shared/api/errors'
import { Button } from '@/shared/ui/button'
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

const inputClass =
  'h-9 w-full rounded-lg border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive'

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
      className="space-y-4"
    >
      <div className="space-y-1">
        <label htmlFor="email" className="text-sm font-medium">
          Email
        </label>
        <input
          id="email"
          type="email"
          autoComplete="username"
          aria-invalid={errors.email ? true : undefined}
          className={inputClass}
          {...register('email')}
        />
        {errors.email && (
          <p role="alert" className="text-sm text-destructive">
            {errors.email.message}
          </p>
        )}
      </div>
      <div className="space-y-1">
        <label htmlFor="password" className="text-sm font-medium">
          Mật khẩu
        </label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          aria-invalid={errors.password ? true : undefined}
          className={inputClass}
          {...register('password')}
        />
        {errors.password && (
          <p role="alert" className="text-sm text-destructive">
            {errors.password.message}
          </p>
        )}
      </div>
      {signIn.isError && (
        <p role="alert" className="text-sm text-destructive">
          {errorMessage(signIn.error)}
        </p>
      )}
      <Button type="submit" disabled={signIn.isPending}>
        {signIn.isPending ? 'Đang đăng nhập…' : 'Đăng nhập'}
      </Button>
    </form>
  )
}
