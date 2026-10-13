import { Navigate, type RouteObject } from 'react-router'
import { WorkshopCatalogPage } from '@/pages/public/WorkshopCatalogPage'
import { WorkshopDetailPage } from '@/pages/public/WorkshopDetailPage'

export const routes: RouteObject[] = [
  { path: '/', element: <Navigate to="/workshops" replace /> },
  { path: '/workshops', element: <WorkshopCatalogPage /> },
  { path: '/workshops/:workshopId', element: <WorkshopDetailPage /> },
]
