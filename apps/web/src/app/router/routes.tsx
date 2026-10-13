import { Navigate, type RouteObject } from 'react-router'

import { WorkshopCatalogPage } from '@/routes/public/WorkshopCatalogPage'
import { WorkshopDetailPage } from '@/routes/public/WorkshopDetailPage'

export const routes: RouteObject[] = [
  { path: '/', element: <Navigate to="/workshops" replace /> },
  { path: '/workshops', element: <WorkshopCatalogPage /> },
  { path: '/workshops/:workshopId', element: <WorkshopDetailPage /> },
]
