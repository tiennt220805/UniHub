import { Navigate, type RouteObject } from 'react-router'
import { WorkshopCatalogPage } from '@/pages/public/WorkshopCatalogPage'

export const routes: RouteObject[] = [
  { path: '/', element: <Navigate to="/workshops" replace /> },
  { path: '/workshops', element: <WorkshopCatalogPage /> },
]
